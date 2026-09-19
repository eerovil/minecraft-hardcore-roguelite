#!/usr/bin/env bash
# Drive the dev environment running in the Mac kubernetes cluster.
#
# Nothing here builds or runs Minecraft locally — the whole point is to keep that
# off this machine. See docs/dev-environment.md.
set -euo pipefail

NS=mhr-dev
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Where the source tree lands in the build pod. The volume is shared, so a test that must not be
# disturbed by someone else's `go` can point at a directory of its own:
#   MHR_WORKSPACE=/pvc/workspace-mine scripts/dev.sh build
WORKSPACE="${MHR_WORKSPACE:-/pvc/workspace}"

usage() {
	cat <<'EOF'
Usage: scripts/dev.sh <command>

  up            Create the namespace, volume and both deployments, then wait for them
  sync          Copy the working tree into the build pod
  build         Run `gradle build` in the build pod (sync first)
  deploy        Put the freshly built jar in the server's mods/ and restart the server
  go            sync + build + deploy, the normal inner loop
  gametest      Run the automated gameplay verification headless in a pod of its own:
                unit tests, server GameTests, client GameTests. PASS/FAIL plus artifacts.
  gametest-shell  Shell in the gametest pod
  logs          Follow the server log
  console       Attach to the server console (Ctrl-P Ctrl-Q to detach)
  rcon <cmd>    Run one server command, e.g. `scripts/dev.sh rcon "mhr list"`
  shell         Shell in the build pod
  newworld      Stop the server, delete the world, start it again
  status        What is running and how far along it is
  down          Delete the deployments but keep the volume
  nuke          Delete everything including the volume
EOF
}

build_pod() {
	kubectl -n "$NS" get pod -l app=mhr-build \
		-o jsonpath='{.items[?(@.status.phase=="Running")].metadata.name}' | awk '{print $1}'
}

require_build_pod() {
	local pod
	pod="$(build_pod)"
	if [[ -z "$pod" ]]; then
		echo "No running build pod. Run: scripts/dev.sh up" >&2
		exit 1
	fi
	echo "$pod"
}

cmd_up() {
	kubectl apply -f "$REPO_ROOT/k8s/dev.yaml"
	echo "Waiting for the build pod..."
	kubectl -n "$NS" rollout status deploy/mhr-build --timeout=5m
	echo "Waiting for the gametest pod (it apt-gets a virtual display on every start)..."
	kubectl -n "$NS" rollout status deploy/mhr-gametest --timeout=10m
	echo "Waiting for the server (first start downloads Minecraft, this takes a while)..."
	kubectl -n "$NS" rollout status deploy/mhr-server --timeout=15m
	cmd_status
}

# $3, when given, is a command prefix each step runs under — the gametest pod is root, and
# everything it writes to the volume has to belong to uid 1000 all the same.
sync_to() {
	local pod="$1" dir="$2"
	local -a as=()
	if [[ -n "${3:-}" ]]; then
		read -r -a as <<<"$3"
	fi
	# Tracked files plus anything new that is not gitignored — the same set a commit would see.
	local files
	files="$(cd "$REPO_ROOT" && git ls-files -co --exclude-standard)"
	if [[ -z "$files" ]]; then
		echo "Nothing to sync" >&2
		exit 1
	fi
	# Wipe the source tree first so deleted files do not linger, but leave build/ and
	# .gradle/ alone: those are the caches that make rebuilds fast.
	kubectl -n "$NS" exec "$pod" -- "${as[@]}" bash -c "mkdir -p $dir && rm -rf $dir/src $dir/k8s $dir/scripts"
	tar -C "$REPO_ROOT" -cf - --files-from=<(printf '%s\n' "$files") \
		| kubectl -n "$NS" exec -i "$pod" -- "${as[@]}" tar -C "$dir" -xf -
	echo "Synced $(printf '%s\n' "$files" | wc -l) files to $pod:$dir"
}

cmd_sync() {
	sync_to "$(require_build_pod)" "$WORKSPACE"
}

cmd_build() {
	local pod
	pod="$(require_build_pod)"
	kubectl -n "$NS" exec -i "$pod" -- bash -lc \
		"cd $WORKSPACE && gradle --no-daemon build ${GRADLE_ARGS:-}"
}

cmd_deploy() {
	local pod fabric_version
	pod="$(require_build_pod)"
	# The server needs Fabric API as a real mod jar, not just as a compile dependency.
	# Same version the mod was built against, read from gradle.properties.
	fabric_version="$(grep '^fabric_version=' "$REPO_ROOT/gradle.properties" | cut -d= -f2)"
	kubectl -n "$NS" exec "$pod" -- bash -lc "
		set -euo pipefail
		mkdir -p /pvc/server/mods
		want=fabric-api-${fabric_version}.jar
		if [ ! -f \"/pvc/server/mods/\$want\" ]; then
			rm -f /pvc/server/mods/fabric-api-*.jar
			echo \"Downloading \$want\"
			curl -fsSL -o \"/pvc/server/mods/\$want\" \\
				\"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${fabric_version}/fabric-api-${fabric_version}.jar\"
		fi
	"
	kubectl -n "$NS" exec "$pod" -- bash -lc '
		set -euo pipefail
		jar=$(ls -t /pvc/workspace/build/libs/*.jar 2>/dev/null | grep -v -e "-sources" -e "-dev" | head -1)
		if [ -z "${jar:-}" ]; then echo "No built jar — run: scripts/dev.sh build" >&2; exit 1; fi
		mkdir -p /pvc/server/mods
		rm -f /pvc/server/mods/hardcore-roguelite*.jar
		cp "$jar" /pvc/server/mods/
		echo "Installed $(basename "$jar")"
	'
	kubectl -n "$NS" rollout restart deploy/mhr-server
	kubectl -n "$NS" rollout status deploy/mhr-server --timeout=10m
}

# --- automated gameplay tests -------------------------------------------------------------
#
# Everything below runs in the mhr-gametest pod, which is the build image plus a virtual display
# and a software OpenGL driver. It never touches the dev server, the dev world or the shared
# workspace: its own source tree, its own Gradle cache, and a run directory Loom wipes before
# every run. See docs/dev-environment.md.

GAMETEST_WORKSPACE=/pvc/gametest/workspace
GAMETEST_AS_USER="setpriv --reuid=1000 --regid=1000 --clear-groups"
GAMETEST_ARTIFACTS="$REPO_ROOT/build/gametest"

gametest_pod() {
	kubectl -n "$NS" get pod -l app=mhr-gametest \
		-o jsonpath='{.items[?(@.status.phase=="Running")].metadata.name}' | awk '{print $1}'
}

require_gametest_pod() {
	local pod
	pod="$(gametest_pod)"
	if [[ -z "$pod" ]]; then
		echo "No running gametest pod. Run: scripts/dev.sh up" >&2
		exit 1
	fi
	echo "$pod"
}

# The body of a test run, as seen from inside the pod. Runs as uid 1000 even though the container
# is root, so that nothing root-owned ends up in the Gradle cache.
gametest_runner() {
	cat <<EOF
set -euo pipefail
export HOME=/pvc/gametest/home
export GRADLE_USER_HOME=/pvc/gradle-gametest
export DISPLAY=:99
# Minecraft 26.3 asks SDL for the OpenGL context. Left to itself SDL goes through GLX, and
# llvmpipe on a bare Xvfb has no GLX visual that matches what the game wants. EGL does.
export SDL_VIDEO_X11_FORCE_EGL=1
mkdir -p "\$HOME"

# One virtual display, reused across runs. Minecraft renders into it with llvmpipe; nothing
# ever looks at the framebuffer except the screenshots the tests take themselves.
if ! xdpyinfo -display :99 >/dev/null 2>&1; then
	Xvfb :99 -screen 0 1280x720x24 -nolisten tcp >/tmp/xvfb.log 2>&1 &
	for _ in \$(seq 1 30); do
		xdpyinfo -display :99 >/dev/null 2>&1 && break
		sleep 1
	done
fi

cd $GAMETEST_WORKSPACE
gradle --no-daemon --console=plain $*
EOF
}

cmd_gametest() {
	local pod
	pod="$(require_gametest_pod)"
	sync_to "$pod" "$GAMETEST_WORKSPACE" "$GAMETEST_AS_USER"

	local status=0
	kubectl -n "$NS" exec -i "$pod" -- bash -c \
		"$GAMETEST_AS_USER bash -s" \
		<<<"$(gametest_runner "${@:-test runGameTest runClientGameTest}")" || status=$?

	gametest_fetch "$pod"

	if [[ $status -eq 0 ]]; then
		echo "PASS — gameplay verification green. Artifacts: $GAMETEST_ARTIFACTS"
	else
		echo "FAIL — gameplay verification red (exit $status). Artifacts: $GAMETEST_ARTIFACTS" >&2
	fi
	return $status
}

# Logs, screenshots and crash reports, pulled back so a failure can be read without a shell in
# the cluster. Always runs, pass or fail — a passing run's screenshots are the visual evidence.
gametest_fetch() {
	local pod="$1"
	rm -rf "$GAMETEST_ARTIFACTS"
	mkdir -p "$GAMETEST_ARTIFACTS"
	kubectl -n "$NS" exec "$pod" -- $GAMETEST_AS_USER bash -c "
		cd $GAMETEST_WORKSPACE/build 2>/dev/null || exit 0
		find run reports/tests -type f \\
			\\( -name '*.log' -o -name '*.txt' -o -name '*.png' -o -name '*.html' -o -name '*.xml' \\) \\
			-print0 2>/dev/null | tar -cf - --null -T - 2>/dev/null
	" | tar -C "$GAMETEST_ARTIFACTS" -xf - 2>/dev/null || true
	local count
	count="$(find "$GAMETEST_ARTIFACTS" -type f | wc -l)"
	echo "Fetched $count artifact files into $GAMETEST_ARTIFACTS"
}

cmd_gametest_shell() {
	kubectl -n "$NS" exec -it "$(require_gametest_pod)" -- bash
}

cmd_logs() {
	kubectl -n "$NS" logs -f deploy/mhr-server --tail=100
}

cmd_console() {
	local pod
	pod="$(kubectl -n "$NS" get pod -l app=mhr-server -o jsonpath='{.items[0].metadata.name}')"
	kubectl -n "$NS" attach -it "$pod"
}

cmd_rcon() {
	local pod
	pod="$(kubectl -n "$NS" get pod -l app=mhr-server -o jsonpath='{.items[0].metadata.name}')"
	kubectl -n "$NS" exec -i "$pod" -- rcon-cli "$@"
}

cmd_shell() {
	local pod
	pod="$(require_build_pod)"
	kubectl -n "$NS" exec -it "$pod" -- bash
}

cmd_newworld() {
	kubectl -n "$NS" scale deploy/mhr-server --replicas=0
	kubectl -n "$NS" wait --for=delete pod -l app=mhr-server --timeout=5m || true
	local pod
	pod="$(require_build_pod)"
	kubectl -n "$NS" exec "$pod" -- bash -c 'rm -rf /pvc/server/world /pvc/server/world_nether /pvc/server/world_the_end'
	kubectl -n "$NS" scale deploy/mhr-server --replicas=1
	kubectl -n "$NS" rollout status deploy/mhr-server --timeout=10m
}

cmd_status() {
	kubectl -n "$NS" get pods -o wide
	echo
	echo "Connect from the Mac with:"
	echo "  kubectl -n $NS port-forward svc/mhr-server 25565:25565"
	echo "then join localhost:25565 (offline mode, any username)."
}

cmd_down() {
	kubectl -n "$NS" delete deploy/mhr-build deploy/mhr-server --ignore-not-found
}

cmd_nuke() {
	kubectl delete namespace "$NS" --ignore-not-found
}

case "${1:-}" in
	up) cmd_up ;;
	sync) cmd_sync ;;
	build) cmd_sync && cmd_build ;;
	deploy) cmd_deploy ;;
	go) cmd_sync && cmd_build && cmd_deploy ;;
	gametest) shift; cmd_gametest "$@" ;;
	gametest-shell) cmd_gametest_shell ;;
	logs) cmd_logs ;;
	console) cmd_console ;;
	rcon) shift; cmd_rcon "$@" ;;
	shell) cmd_shell ;;
	newworld) cmd_newworld ;;
	status) cmd_status ;;
	down) cmd_down ;;
	nuke) cmd_nuke ;;
	*) usage; exit 1 ;;
esac
