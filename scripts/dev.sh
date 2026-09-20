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

# How long a command waits for a pod that somebody else is using before giving up.
MHR_LOCK_WAIT="${MHR_LOCK_WAIT:-2700}"

usage() {
	cat <<'EOF'
Usage: scripts/dev.sh <command>

  image         Print the command that builds the gametest image and loads it into the
                cluster node. It has to run on the Mac — see docs/dev-environment.md.
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

The cluster is shared. Commands that use a pod's workspace take that pod's lock and wait for it
rather than running on top of each other; MHR_LOCK_WAIT (seconds) bounds the wait. `shell`,
`gametest-shell`, `console`, `logs` and `rcon` take no lock — a shell you leave open would block
everybody else.
EOF
}

# --- the shared-cluster run lock ----------------------------------------------------------
#
# There is one build pod and one gametest pod, and each holds state a second concurrent run
# destroys: sync_to wipes the source tree before copying it again, and the gametest pod has one
# network namespace, so only one dedicated test server can ever hold port 25565. A command that
# uses a pod therefore takes that pod's lock and holds it for the whole command. The two locks are
# separate, so a build and a gametest still run at the same time.
#
# The lock is an flock held by a `kubectl exec` sitting on a pipe this script keeps open. That is
# the whole reason it cannot go stale: if this script exits, crashes, is killed or loses its
# connection, the connection to the pod drops, the shell in the pod dies with it and the kernel
# drops the lock. There is nothing to clean up after a dead worker and no unlock command.
#
# Two things end the run in the pod, because one of them is not enough:
#
#   - we close the pipe, the `cat` in the pod sees EOF and exits by itself. This is the tidy exit
#     and the one a normal `release_lock` takes;
#   - the watchdog below drops the kubectl connection. This is the backstop for a `kill -9` of this
#     script, which runs no traps and closes nothing in the children we already started.
#
# And the guard watches the other way round, because the lock can also die while we live: if that
# `kubectl exec` loses its connection, the kernel in the pod drops the flock and somebody else may
# take the pod while our gradle is still running in it. Losing the lock has to end the run.
LOCK_TMP=""
LOCK_PID=""
LOCK_FD=""
LOCK_WATCHDOG_PID=""
LOCK_GUARD_PID=""

release_lock() {
	[[ -n "$LOCK_PID" ]] || return 0
	# Both of these have to go before the holder does, or the guard would read our own deliberate
	# release as the lock dying under us.
	if [[ -n "$LOCK_GUARD_PID" ]]; then
		kill "$LOCK_GUARD_PID" 2>/dev/null || true
		wait "$LOCK_GUARD_PID" 2>/dev/null || true
		LOCK_GUARD_PID=""
	fi
	if [[ -n "$LOCK_WATCHDOG_PID" ]]; then
		kill "$LOCK_WATCHDOG_PID" 2>/dev/null || true
		wait "$LOCK_WATCHDOG_PID" 2>/dev/null || true
		LOCK_WATCHDOG_PID=""
	fi
	# Close our end first: the holder's `cat` sees EOF and releases the lock on its own, which is
	# tidier than killing the connection and letting the kubelet reap the process. It only works
	# when no child of ours is still holding the pipe open, hence the short wait and the kill.
	[[ -n "$LOCK_FD" ]] && exec {LOCK_FD}>&- && LOCK_FD=""
	local spun=0
	while kill -0 "$LOCK_PID" 2>/dev/null && ((spun < 25)); do
		sleep 0.2
		spun=$((spun + 1))
	done
	kill "$LOCK_PID" 2>/dev/null || true
	wait "$LOCK_PID" 2>/dev/null || true
	LOCK_PID=""
	[[ -n "$LOCK_TMP" ]] && rm -rf "$LOCK_TMP"
	LOCK_TMP=""
}

# take_lock <pod> <name>. Blocks until the lock is ours or MHR_LOCK_WAIT runs out.
take_lock() {
	local pod="$1" name="$2"
	local lock="/pvc/.mhr-lock-$name"
	local owner="${USER:-someone}@$(hostname -s 2>/dev/null || echo unknown), started $(date '+%H:%M:%S')"

	LOCK_TMP="$(mktemp -d)"
	mkfifo "$LOCK_TMP/hold"
	# Opened read-write so the pipe has a writer for as long as this script lives and the holder
	# never sees a premature EOF. (Read-write rather than write-only because opening a fifo for
	# writing blocks until somebody opens the read end, and nothing has yet.)
	exec {LOCK_FD}<>"$LOCK_TMP/hold"

	# `{LOCK_FD}>&-` closes our writer inside the kubectl child, and it is what makes the lock
	# survive a `kill -9` of this script. A background command inherits every descriptor the shell
	# has open, so without it the child would be holding a writer on the fifo too: this script could
	# die outright, the orphaned child would keep the pipe from ever reaching EOF, and the `cat` in
	# the pod would sit on the flock forever with nobody left to release it.
	kubectl -n "$NS" exec -i "$pod" -- bash -c "
		exec 200>>'$lock'
		if ! flock -w '$MHR_LOCK_WAIT' 200; then
			echo \"BUSY \$(cat '$lock.owner' 2>/dev/null)\"
			exit 1
		fi
		echo '$owner' >'$lock.owner'
		echo HELD
		cat >/dev/null
	" <"$LOCK_TMP/hold" >"$LOCK_TMP/out" 2>&1 {LOCK_FD}>&- &
	LOCK_PID=$!

	# Bash has no way to mark a descriptor close-on-exec, so every command this script runs while it
	# holds the lock — gradle, tar, even sleep — inherits our end of the pipe. Harmless while we are
	# alive; fatal after a `kill -9`, because those children carry on holding the pipe open, the
	# `cat` in the pod never sees EOF and the lock outlives the worker that took it. So the watchdog
	# waits for this script's pid to go away and then drops the connection itself. It is a child too,
	# so it survives our death — that is exactly what makes it able to clean up after it.
	local me=$$ holder=$LOCK_PID
	(
		while kill -0 "$me" 2>/dev/null; do sleep 1; done
		kill "$holder" 2>/dev/null || true
	) &
	LOCK_WATCHDOG_PID=$!

	trap release_lock EXIT
	# Bash does not run an EXIT trap when the shell dies of an untrapped signal, and a Ctrl-C that
	# reached us but not the holder would otherwise leave it orphaned and the lock held.
	trap 'exit 130' INT TERM HUP

	local waited=0
	while true; do
		if grep -q '^HELD' "$LOCK_TMP/out" 2>/dev/null; then
			# A second or two is just the exec starting up, not a queue worth reporting.
			((waited >= 3)) && echo "Got the $name pod after ${waited}s."
			# Armed only now: until HELD the holder is *expected* to exit, that is how a lost race
			# and a timeout report themselves, and the loop below handles it.
			(
				while kill -0 "$holder" 2>/dev/null; do sleep 1; done
				kill -0 "$me" 2>/dev/null || exit 0
				echo "" >&2
				echo "Lost the $name pod lock: the kubectl exec holding it has gone." >&2
				echo "Another run can take the pod now, so this one stops here rather than" >&2
				echo "carrying on unprotected. Nothing is wrong with your change; run it again." >&2
				# The whole process group, because the command we are protecting is a child of ours
				# and a plain SIGTERM to a bash sitting in a foreground command is not read until
				# that command returns — which, for a gametest, is many minutes away. The group
				# signal only works when we lead the group, which is true when a person ran the
				# script and not always true when something else did, hence the fallback.
				kill -TERM "$me" 2>/dev/null || true
				kill -TERM -"$me" 2>/dev/null || pkill -TERM -P "$me" 2>/dev/null || true
			) &
			LOCK_GUARD_PID=$!
			return 0
		fi
		if ! kill -0 "$LOCK_PID" 2>/dev/null; then
			echo "Gave up waiting for the $name pod after ${MHR_LOCK_WAIT}s." >&2
			sed 's/^BUSY /  held by: /;s/^  held by: $/  held by: someone who left no name/' \
				"$LOCK_TMP/out" >&2
			echo "  Raise MHR_LOCK_WAIT to wait longer." >&2
			release_lock
			exit 1
		fi
		if ((waited == 5)); then
			local who
			who="$(kubectl -n "$NS" exec "$pod" -- cat "$lock.owner" 2>/dev/null || true)"
			echo "Another run is using the $name pod${who:+ ($who)}. Waiting up to ${MHR_LOCK_WAIT}s..."
		fi
		sleep 1
		waited=$((waited + 1))
	done
}

# Run the given steps with the build pod's lock held across all of them, so nobody can sync on top
# of a build that is already under way.
build_locked() {
	take_lock "$(require_build_pod)" build
	local step
	for step in "$@"; do
		"$step"
	done
	release_lock
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

# The gametest image is built on the Mac, because the only cluster node is the Mac and it is
# arm64 while this machine is x86_64. There is no registry anywhere: the built image is imported
# straight into the node container's containerd, which is what `kind load docker-image` does and
# what Docker Desktop's kind-based kubernetes leaves room for. containerd refuses plain-HTTP
# registries even on localhost, so an in-cluster registry would need a certificate or a
# node-level hosts.toml that Docker Desktop resets — this route needs neither.
#
# The command is generated rather than kept as a second file so that k8s/gametest.Dockerfile
# stays the only copy of what goes into the image.
cmd_image() {
	local tag dockerfile
	dockerfile="$REPO_ROOT/k8s/gametest.Dockerfile"
	tag="$(grep -o 'mhr-gametest:[^ ]*' "$REPO_ROOT/k8s/dev.yaml" | head -1)"
	if [[ -z "$tag" ]]; then
		echo "No mhr-gametest tag in k8s/dev.yaml" >&2
		exit 1
	fi
	cat <<EOF
set -eu
# Build $tag for the cluster node and import it into that node's containerd.
docker inspect --format 'node container: {{.State.Status}}' desktop-control-plane
ctx=\$(mktemp -d)
cat > "\$ctx/Dockerfile" <<'MHR_DOCKERFILE'
$(cat "$dockerfile")
MHR_DOCKERFILE
docker build --platform linux/arm64 -t $tag "\$ctx"
rm -rf "\$ctx"
docker save $tag | docker exec -i desktop-control-plane ctr -n k8s.io images import -
# Prove it landed. grep fails the whole command if it did not.
docker exec desktop-control-plane ctr -n k8s.io images ls | grep '$tag'
EOF
}

cmd_up() {
	kubectl apply -f "$REPO_ROOT/k8s/dev.yaml"
	echo "Waiting for the build pod..."
	kubectl -n "$NS" rollout status deploy/mhr-build --timeout=5m
	echo "Waiting for the gametest pod (needs the baked image: scripts/dev.sh image)..."
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

# The test pod's source tree. Shared, like the build pod's, so two people running `gametest` at
# once overwrite each other's `src/` halfway through a run — which shows up as somebody else's
# tests failing in your output. Point this somewhere of your own to stay out of the way:
#   MHR_GAMETEST_WORKSPACE=/pvc/gametest/workspace-mine scripts/dev.sh gametest
# The Gradle cache stays shared either way, which is the expensive part.
GAMETEST_WORKSPACE="${MHR_GAMETEST_WORKSPACE:-/pvc/gametest/workspace}"
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
# llvmpipe on a bare Xvfb has no GLX visual that matches what the game wants, so the client dies
# on "Couldn't find matching GLX visual" before a single test runs. EGL has no such problem.
# The SDL2 spelling of this was SDL_VIDEO_X11_FORCE_EGL and SDL3 ignores it.
export SDL_VIDEO_FORCE_EGL=1
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

# Counting, then killing, the java processes in the pod. Read straight out of /proc so this keeps
# working if the pod image ever stops shipping procps.
#
# Match the executable name, not the whole command line. The helper below is `bash -c <this script
# text>`, so its own /proc/<pid>/cmdline contains the word we are looking for: a substring match
# finds the scanner itself, which makes an idle pod look busy and puts the killer on its own list.
#
# An empty cmdline means the process has none: a kernel thread, or — and the pod collects hundreds
# of these — a JVM that has exited and is waiting to be reaped. The pod's pid 1 is a `sleep`, which
# never reaps anything, so dead JVMs stay in /proc as zombies named `java` forever. A zombie holds
# no port and cannot be killed, so counting one would keep an idle pod looking busy for good.
GAMETEST_JVM_SCAN='
	is_java() {
		argv0=$(tr "\0" "\n" 2>/dev/null <"$1/cmdline" | head -n 1)
		[ -n "$argv0" ] || return 1
		[ "${argv0##*/}" = java ] && return 0
		comm=""
		read -r comm 2>/dev/null <"$1/comm" || return 1
		[ "$comm" = java ]
	}
'

# Both of these fail rather than pretend. A kubectl that cannot reach the pod used to come back as
# "no JVMs here", which is the one answer that lets the run carry straight on into the port-25565
# collision this check exists to prevent.
gametest_jvms() {
	local out
	out="$(kubectl -n "$NS" exec "$1" -- bash -c "$GAMETEST_JVM_SCAN"'
		n=0
		for p in /proc/[0-9]*; do
			is_java "$p" && n=$((n + 1))
		done
		echo "$n"
	' 2>&1)" || { echo "$out" >&2; return 1; }
	# A count and nothing else. Anything the transport prints on its way through is a failure too.
	[[ "$out" =~ ^[0-9]+$ ]] || { echo "$out" >&2; return 1; }
	echo "$out"
}

gametest_kill_jvms() {
	local out
	out="$(kubectl -n "$NS" exec "$1" -- bash -c "$GAMETEST_JVM_SCAN"'
		for p in /proc/[0-9]*; do
			is_java "$p" && kill -9 "${p#/proc/}" 2>/dev/null
		done
		true
	' 2>&1)" || { echo "$out" >&2; return 1; }
}

# We hold the lock, so nothing that respects the lock is running. A JVM alive in the pod anyway is
# one of two things, and they need opposite treatment:
#
#   - debris from a run that died, typically a client JVM still holding port 25565, which would
#     fail this run with `FAILED TO BIND TO PORT` for somebody else's reason;
#   - a live run started by someone who is not taking the lock — an older checkout of this script,
#     or a `gradle` typed into `gametest-shell`.
#
# Killing the first is required and killing the second destroys somebody's work, and they look
# identical from here. So wait first: a real run finishes, debris never does. When the grace runs
# out we kill, which is what #40 asks for — by then the pod has been idle-but-occupied for ten
# minutes with the lock in our hands, and debris is much the likelier of the two. Somebody who
# knows they are running without the lock can hold the run off with MHR_KILL_STRAYS=0.
MHR_STRAY_GRACE="${MHR_STRAY_GRACE:-600}"
MHR_KILL_STRAYS="${MHR_KILL_STRAYS:-1}"

gametest_clear_strays() {
	local pod="$1" n

	if ! n="$(gametest_jvms "$pod")"; then
		echo "Could not look for leftover JVMs in the gametest pod." >&2
		echo "Stopping: a check that did not run is not the same as a pod that is clear." >&2
		exit 1
	fi
	[[ "$n" == "0" ]] && return 0

	echo "A JVM is running in the gametest pod even though we hold the lock. That is either debris"
	echo "from a run that died, or somebody running without the lock. Waiting up to ${MHR_STRAY_GRACE}s..."
	local waited=0
	while ((waited < MHR_STRAY_GRACE)); do
		sleep 10
		waited=$((waited + 10))
		# A scan that fails here is not fatal — we are waiting anyway, and the next one is ten
		# seconds away. Only a scan that succeeds *and* says zero lets the run through.
		if n="$(gametest_jvms "$pod")" && [[ "$n" == "0" ]]; then
			echo "It finished after ${waited}s. Carrying on."
			return 0
		fi
	done

	if [[ "$MHR_KILL_STRAYS" != "1" ]]; then
		cat >&2 <<EOF
Still there after ${MHR_STRAY_GRACE}s, and MHR_KILL_STRAYS is not 1, so this run stops here.
Running anyway would fail on port 25565 for somebody else's reason.

  Somebody is working without the lock  -> wait, or ask them.
  It is debris from a run that died     -> rerun without MHR_KILL_STRAYS=0, or clear it by hand:
                                           kubectl -n $NS exec deploy/mhr-gametest -- pkill -f KnotClient

To look first:
  kubectl -n $NS exec deploy/mhr-gametest -- ps -ef
EOF
		exit 1
	fi

	echo "Still there after ${MHR_STRAY_GRACE}s. Treating it as debris from a dead run and killing it."
	if ! gametest_kill_jvms "$pod"; then
		echo "Could not kill the leftover JVMs in the gametest pod. Stopping." >&2
		exit 1
	fi

	# Killing is not the same as gone: the kill can have missed, and a JVM takes a moment to die.
	# Nothing may run until a scan that actually succeeded says the pod is clear.
	local tries=0
	while ((tries < 10)); do
		sleep 1
		tries=$((tries + 1))
		if n="$(gametest_jvms "$pod")" && [[ "$n" == "0" ]]; then
			echo "The pod is clear."
			return 0
		fi
	done

	cat >&2 <<EOF
Killed the leftover JVMs but the pod does not look clear afterwards, so this run stops here.
Something is restarting them, or the pod cannot be reached. Have a look:

  kubectl -n $NS exec deploy/mhr-gametest -- ps -ef
EOF
	exit 1
}

cmd_gametest() {
	local pod
	pod="$(require_gametest_pod)"
	take_lock "$pod" gametest
	gametest_clear_strays "$pod"
	sync_to "$pod" "$GAMETEST_WORKSPACE" "$GAMETEST_AS_USER"

	local status=0
	kubectl -n "$NS" exec -i "$pod" -- bash -c \
		"$GAMETEST_AS_USER bash -s" \
		<<<"$(gametest_runner "${@:-test runGameTest runClientGameTest}")" || status=$?

	gametest_fetch "$pod"
	release_lock

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
	image) cmd_image ;;
	up) cmd_up ;;
	sync) build_locked cmd_sync ;;
	build) build_locked cmd_sync cmd_build ;;
	deploy) build_locked cmd_deploy ;;
	go) build_locked cmd_sync cmd_build cmd_deploy ;;
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
