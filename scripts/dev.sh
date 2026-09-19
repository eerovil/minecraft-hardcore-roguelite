#!/usr/bin/env bash
# Drive the dev environment running in the Mac kubernetes cluster.
#
# Nothing here builds or runs Minecraft locally — the whole point is to keep that
# off this machine. See docs/dev-environment.md.
set -euo pipefail

NS=mhr-dev
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKSPACE=/pvc/workspace

usage() {
	cat <<'EOF'
Usage: scripts/dev.sh <command>

  up            Create the namespace, volume and both deployments, then wait for them
  sync          Copy the working tree into the build pod
  build         Run `gradle build` in the build pod (sync first)
  deploy        Put the freshly built jar in the server's mods/ and restart the server
  go            sync + build + deploy, the normal inner loop
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
	echo "Waiting for the server (first start downloads Minecraft, this takes a while)..."
	kubectl -n "$NS" rollout status deploy/mhr-server --timeout=15m
	cmd_status
}

cmd_sync() {
	local pod
	pod="$(require_build_pod)"
	# Tracked files plus anything new that is not gitignored — the same set a commit would see.
	local files
	files="$(cd "$REPO_ROOT" && git ls-files -co --exclude-standard)"
	if [[ -z "$files" ]]; then
		echo "Nothing to sync" >&2
		exit 1
	fi
	# Wipe the source tree first so deleted files do not linger, but leave build/ and
	# .gradle/ alone: those are the caches that make rebuilds fast.
	kubectl -n "$NS" exec "$pod" -- bash -c "mkdir -p $WORKSPACE && rm -rf $WORKSPACE/src $WORKSPACE/k8s $WORKSPACE/scripts"
	tar -C "$REPO_ROOT" -cf - --files-from=<(printf '%s\n' "$files") \
		| kubectl -n "$NS" exec -i "$pod" -- tar -C "$WORKSPACE" -xf -
	echo "Synced $(printf '%s\n' "$files" | wc -l) files to $pod:$WORKSPACE"
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
