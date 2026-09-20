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
# destroys: installing the source wipes the tree before writing it again, and the gametest pod has
# a single network namespace, so only one dedicated test server can ever hold port 25565. A command
# that uses a pod therefore takes that pod's lock for the whole command. The two locks are
# separate, so a build and a gametest still run at the same time.
#
# One process owns the critical section. The shell in the pod that takes the flock is the shell
# that runs the work: everything needing the pod exclusively is written as a script, staged into
# the pod and run there. Nothing on this machine holds the lock.
#
# The work runs as a child of the shell holding the lock, so it inherits the lock's file
# descriptor, and the kernel drops an flock only when the last descriptor on it closes. The lock
# therefore outlives the work by construction rather than by arrangement: there is no window
# between the lock going and the work stopping for a second worker to walk into. (A JVM gradle
# forks does not reliably keep that descriptor — measured — so this covers the run, not every
# process a run can leave behind. What a run leaves behind is the stray-JVM check's business.)
#
# What then has to be true is the other half: when this script dies, the work in the pod has to die
# too, or the pod stays locked by a run nobody is watching. The pod can see us go. Killing the
# local `kubectl`, losing the connection or killing this script all end the exec stream, and the
# kubelet closes the remote end's stdin when that happens — so a `read` in the pod returns EOF.
# (The two more obvious signals are no good: the shell in the pod is *not* killed when the
# connection dies, and writes to its stdout keep succeeding afterwards. Both measured.)
#
# So the runner in the pod reads that stdin, and on EOF kills every process holding the lock —
# which is the work, its children, and the runner itself. The lock is released by the last of them
# dying, which is still the only way it is ever released.
LOCK_BUSY_STATUS=75

# stage_in_pod <pod> <command-prefix> <suffix>: reads a file on stdin, puts it somewhere of its own
# in the pod, prints the path. The name comes from mktemp in the pod rather than from our pid,
# because /pvc is shared across machines and containers and a pid is unique in neither.
stage_in_pod() {
	local pod="$1" suffix="${3:-}"
	local -a as=()
	if [[ -n "${2:-}" ]]; then
		read -r -a as <<<"$2"
	fi
	kubectl -n "$NS" exec -i "$pod" -- "${as[@]}" bash -c \
		"f=\$(mktemp /pvc/.mhr-XXXXXXXX$suffix) && cat >\"\$f\" && echo \"\$f\""
}

# The runner, as the pod sees it: take the lock, run the work under it, and watch for us going away.
lock_runner() {
	printf 'lock=%q\nname=%q\nowner=%q\nwork=%q\nas=%q\nhold=%q\nwait_secs=%q\nbusy=%q\n' \
		"$1" "$2" "$3" "$4" "$5" "$6" "$MHR_LOCK_WAIT" "$LOCK_BUSY_STATUS"
	cat <<'EOF'
set -uo pipefail

# Stopping the work, in the order that keeps the lock honest: everything the work started first,
# and only then whatever still holds the lock — which ends with this runner, and with the lock.
#
# Both halves are needed. The tree is what the work actually is, but a JVM gradle forks does not
# reliably inherit the lock descriptor, so holders alone would leave one running; and a process
# reparented away from the tree would be missed by the tree walk alone.
kill_tree() {
	local kid
	for kid in $(cat /proc/"$1"/task/*/children 2>/dev/null); do
		kill_tree "$kid"
	done
	kill -9 "$1" 2>/dev/null || true
}

kill_lock_holders() {
	for p in /proc/[0-9]*; do
		for fd in "$p"/fd/*; do
			if [ "$(readlink "$fd" 2>/dev/null)" = "$lock" ]; then
				kill -9 "${p#/proc/}" 2>/dev/null || true
				break
			fi
		done
	done
}

stop_everything() {
	kill_tree "$work_pid"
	kill_lock_holders
}

if ! exec 200>>"$lock"; then
	echo "Cannot open $lock in the pod, so this run cannot take the $name lock." >&2
	exit 1
fi

# Contention is whatever this non-blocking attempt says it is. Asking after a fixed number of
# seconds instead would say nothing at all about a queue that cleared in fewer.
if ! flock -n 200; then
	echo "Another run is using the $name pod ($(cat "$lock.owner" 2>/dev/null || echo 'no name left'))."
	echo "Waiting up to ${wait_secs}s..."
	began=$SECONDS
	if ! flock -w "$wait_secs" 200; then
		{
			echo "Gave up waiting for the $name pod after ${wait_secs}s."
			echo "  held by: $(cat "$lock.owner" 2>/dev/null || echo 'someone who left no name')"
			echo "  Raise MHR_LOCK_WAIT to wait longer."
		} >&2
		exit "$busy"
	fi
	echo "Got the $name pod after $((SECONDS - began))s."
fi
echo "$owner" >"$lock.owner"

go="$work.go"
rm -f "$go"

# A copy of our stdin for the watcher. Bash points an asynchronous command's stdin at /dev/null
# when job control is off, which is always here — so a watcher reading plain stdin would see EOF
# the instant it started and take the whole run down with it.
exec 9<&0

# The work. A child of this shell, so it holds this shell's lock descriptor; stdin closed, because
# this shell's stdin is the line the other end talks to us on and gradle reads whatever stdin it
# is given.
$as bash "$work" </dev/null &
work_pid=$!

# The watcher, with no lock descriptor of its own so that it outlives the killing below. Reading
# our stdin is how we notice the other end has gone: EOF there means the exec stream is over.
(
	exec 200>&-
	while IFS= read -r line <&9; do
		[ "$line" = MHR-GO ] && : >"$go"
	done
	stop_everything
) &
watcher=$!

wait "$work_pid"
status=$?

# Some commands have a step on the other end that belongs inside this section — a deploy's server
# rollout. Hold the lock while that runs; the watcher still has us covered if they die during it.
#
# Only when the work went well, though. The step is the second half of something whose first half
# has just failed: a deploy whose jar never got copied has nothing to restart the server for, and
# restarting it anyway would put the previous jar back into service and report a failure at the
# same time.
if [ -n "$hold" ] && [ "$status" -eq 0 ]; then
	echo MHR-CONTROL-HOLD
	while [ ! -e "$go" ]; do sleep 1; done
fi

kill "$watcher" 2>/dev/null || true
rm -f "$work" "$go" "$0"
exit "$status"
EOF
}

# The other end of MHR-CONTROL-HOLD: run the local step, then let the pod finish. Only used by the
# commands that have such a step, because every line of output goes through this loop.
lock_control() {
	local step="$1" fifo="$2" status_file="$3" line
	while IFS= read -r line; do
		if [[ "$line" == MHR-CONTROL-HOLD ]]; then
			"$step" || printf '%s\n' "$?" >"$status_file"
			printf 'MHR-GO\n' >"$fifo"
		else
			printf '%s\n' "$line"
		fi
	done
}

# run_locked <pod> <lock-name> [command-prefix] [local-step]; the protected script arrives on stdin.
run_locked() {
	local pod="$1" name="$2" as="${3:-}" step="${4:-}"
	local lock="/pvc/.mhr-lock-$name"
	local owner="${USER:-someone}@$(hostname -s 2>/dev/null || echo unknown), started $(date '+%H:%M:%S')"
	local body work runner tmp fd status=0
	body="$(cat)"

	work="$(printf '%s\n' "$body" | stage_in_pod "$pod" "$as" .sh)"
	runner="$(lock_runner "$lock" "$name" "$owner" "$work" "$as" "$step" | stage_in_pod "$pod" "" .sh)"

	# The fifo is the line the pod watches. This shell has to be the only thing holding it open, so
	# that it reaches EOF when this shell stops existing, whatever stops it. Both halves of the
	# pipeline below inherit the descriptor and both have to give it up: a surviving writer
	# anywhere here is our death going unnoticed in the pod, with the lock still held. The control
	# half writes to the fifo by name when it has something to say, which is why it can let go of
	# the descriptor and still be heard.
	tmp="$(mktemp -d)"
	mkfifo "$tmp/hold"
	exec {fd}<>"$tmp/hold"

	if [[ -n "$step" ]]; then
		kubectl -n "$NS" exec -i "$pod" -- bash "$runner" <"$tmp/hold" 2>&1 {fd}>&- \
			| lock_control "$step" "$tmp/hold" "$tmp/step-status" {fd}>&-
		status="${PIPESTATUS[0]}"
		if [[ -s "$tmp/step-status" ]]; then
			status="$(cat "$tmp/step-status")"
		fi
	else
		kubectl -n "$NS" exec -i "$pod" -- bash "$runner" <"$tmp/hold" {fd}>&- || status=$?
	fi

	exec {fd}>&-
	rm -rf "$tmp"

	if ((status == LOCK_BUSY_STATUS)); then
		exit 1
	fi
	return "$status"
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

# Copying the source in happens in two halves, because only the second half needs the pod to
# itself. The tarball lands in a file of its own outside the lock — it disturbs nobody — and the
# part that wipes and rewrites the shared tree runs inside the critical section.
#
# $2, when given, is a command prefix the copy runs under: the gametest pod is root, and everything
# it writes to the volume has to belong to uid 1000 all the same.
STAGED_FILE_COUNT=0
STAGED_TARBALL=""

# Sets STAGED_TARBALL and STAGED_FILE_COUNT rather than printing the path, because a command
# substitution would run this in a subshell and lose them.
stage_source() {
	local pod="$1"
	local -a as=()
	if [[ -n "${2:-}" ]]; then
		read -r -a as <<<"$2"
	fi
	# Tracked files plus anything new that is not gitignored — the same set a commit would see.
	local files
	files="$(cd "$REPO_ROOT" && git ls-files -co --exclude-standard)"
	if [[ -z "$files" ]]; then
		echo "Nothing to sync" >&2
		exit 1
	fi
	STAGED_FILE_COUNT="$(printf '%s\n' "$files" | wc -l | tr -d ' ')"
	STAGED_TARBALL="$(tar -C "$REPO_ROOT" -cf - --files-from=<(printf '%s\n' "$files") \
		| stage_in_pod "$pod" "${2:-}" .tar)"
}

# The fragments below are the protected work, as the pod sees it. Each one prints a script rather
# than running anything: the caller strings the ones it needs together and hands the result to
# run_locked, which is what makes a whole command one critical section.
remote_install_source() {
	local dir="$1" stage="$2"
	printf 'dir=%q\nstage=%q\ncount=%q\n' "$dir" "$stage" "$STAGED_FILE_COUNT"
	cat <<'EOF'
# Wipe the source tree first so deleted files do not linger, but leave build/ and .gradle/ alone:
# those are the caches that make rebuilds fast.
mkdir -p "$dir"
rm -rf "$dir/src" "$dir/k8s" "$dir/scripts"
tar -C "$dir" -xf "$stage"
rm -f "$stage"
echo "Synced $count files to $dir"
EOF
}

remote_gradle_build() {
	printf 'ws=%q\nargs=%q\n' "$WORKSPACE" "${GRADLE_ARGS:-}"
	cat <<'EOF'
cd "$ws"
# shellcheck disable=SC2086
gradle --no-daemon build $args
EOF
}

remote_install_jar() {
	# The server needs Fabric API as a real mod jar, not just as a compile dependency. Same version
	# the mod was built against, read from gradle.properties.
	printf 'fabric_version=%q\n' \
		"$(grep '^fabric_version=' "$REPO_ROOT/gradle.properties" | cut -d= -f2)"
	cat <<'EOF'
mkdir -p /pvc/server/mods
want="fabric-api-${fabric_version}.jar"
if [ ! -f "/pvc/server/mods/$want" ]; then
	rm -f /pvc/server/mods/fabric-api-*.jar
	echo "Downloading $want"
	curl -fsSL -o "/pvc/server/mods/$want" \
		"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${fabric_version}/fabric-api-${fabric_version}.jar"
fi
jar=$(ls -t /pvc/workspace/build/libs/*.jar 2>/dev/null | grep -v -e "-sources" -e "-dev" | head -1)
if [ -z "${jar:-}" ]; then echo "No built jar — run: scripts/dev.sh build" >&2; exit 1; fi
rm -f /pvc/server/mods/hardcore-roguelite*.jar
cp "$jar" /pvc/server/mods/
echo "Installed $(basename "$jar")"
EOF
}

# sync, build, deploy and go are the same critical section with different steps in it, so they are
# one function. Restarting the server is deliberately outside: it is the server pod's business, not
# the build pod's, and holding the build lock through a rollout would block everybody for nothing.
cmd_build_pod() {
	local pod body="" step restart=0
	pod="$(require_build_pod)"

	for step in "$@"; do
		case "$step" in
			sync)
				stage_source "$pod"
				body+="$(remote_install_source "$WORKSPACE" "$STAGED_TARBALL")"$'\n'
				;;
			build) body+="$(remote_gradle_build)"$'\n' ;;
			deploy)
				body+="$(remote_install_jar)"$'\n'
				restart=1
				;;
		esac
	done

	# The rollout is part of a deploy's critical section, not an afterthought to it. The jar and the
	# server that loads it are one shared thing: two deploys overlapping there means one of them
	# restarts the server onto the other one's jar and reports success. So the pod keeps the lock
	# while `rollout_server` runs on this end.
	local hold=""
	((restart)) && hold=rollout_server
	run_locked "$pod" build "" "$hold" <<<"set -euo pipefail"$'\n'"$body"
}

rollout_server() {
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
status=0
gradle --no-daemon --console=plain $* || status=\$?
EOF
}

# Logs, screenshots and crash reports, collected into one file inside the critical section so that
# a second run cannot wipe the tree out from under the copy, and pulled out afterwards. Always
# runs, pass or fail — a passing run's screenshots are the visual evidence.
remote_pack_artifacts() {
	printf 'ws=%q\nout=%q\n' "$GAMETEST_WORKSPACE" "$1"
	cat <<'EOF'
rm -f "$out"
if cd "$ws/build" 2>/dev/null; then
	find run reports/tests -type f \
		\( -name '*.log' -o -name '*.txt' -o -name '*.png' -o -name '*.html' -o -name '*.xml' \) \
		-print0 2>/dev/null | tar -cf "$out" --null -T - 2>/dev/null || true
fi
echo "Packed $(tar -tf "$out" 2>/dev/null | wc -l) artifact files"
exit $status
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

# The check runs in the pod, inside the critical section, so there is no transport between the
# question and the answer: it cannot come back as a reassuring "no JVMs here" because kubectl could
# not reach the pod. If the script cannot run at all, the run does not start.

# We hold the lock, so nothing that respects the lock is running. A JVM alive in the pod anyway is
# one of two things, and they need opposite treatment:
#
#   - debris from a run that died, typically a client JVM still holding port 25565, which would
#     fail this run with `FAILED TO BIND TO PORT` for somebody else's reason;
#   - a live run started by someone who is not taking the lock — an older checkout of this script,
#     or a `gradle` typed into `gametest-shell`.
#
# Killing the first is required and killing the second destroys somebody's work, and they look
# identical from here. So wait first: a real run finishes, debris never does. But waiting only
# narrows the ambiguity, it does not remove it — a run can simply be slower than the grace period.
# So when the grace runs out we stop and say what we found rather than guessing, and killing is a
# decision the person at the keyboard makes with MHR_KILL_STRAYS=1.
#
# This is not theoretical caution. While #40 was being written, a session testing this very code
# killed what it took for debris and it was another worker's live client gametest, started from a
# branch that predates the lock. Until the lock is on main and every branch in flight carries it,
# a JVM in that pod with the lock free is at least as likely to be somebody working as it is to be
# rubbish. When that stops being true the default can flip, and #40's "killed automatically"
# criterion is met by MHR_KILL_STRAYS=1 rather than by the default.
MHR_STRAY_GRACE="${MHR_STRAY_GRACE:-600}"
MHR_KILL_STRAYS="${MHR_KILL_STRAYS:-0}"

remote_clear_strays() {
	printf 'ns=%q\ngrace=%q\nkill_strays=%q\n' "$NS" "$MHR_STRAY_GRACE" "$MHR_KILL_STRAYS"
	printf '%s\n' "$GAMETEST_JVM_SCAN"
	cat <<'EOF'
jvms() {
	n=0
	for p in /proc/[0-9]*; do
		if is_java "$p"; then n=$((n + 1)); fi
	done
	echo "$n"
}

if [ "$(jvms)" != 0 ]; then
	echo "A JVM is running in the gametest pod even though this run holds the lock. That is either"
	echo "debris from a run that died, or somebody running without the lock. Waiting up to ${grace}s..."
	waited=0
	while [ "$waited" -lt "$grace" ]; do
		sleep 10
		waited=$((waited + 10))
		if [ "$(jvms)" = 0 ]; then break; fi
	done

	if [ "$(jvms)" = 0 ]; then
		echo "It finished after ${waited}s. Carrying on."
	elif [ "$kill_strays" != 1 ]; then
		cat >&2 <<MSG
Still there after ${grace}s. Stopping rather than guessing: running now would fail on
port 25565 anyway, and killing it might destroy somebody's run.

  Somebody is working without the lock  -> wait, or ask them.
  It is debris from a run that died     -> rerun with MHR_KILL_STRAYS=1, or clear it by hand:
                                           kubectl -n $ns exec deploy/mhr-gametest -- pkill -f KnotClient

To look first:
  kubectl -n $ns exec deploy/mhr-gametest -- ps -ef
MSG
		exit 1
	else
		echo "Still there after ${grace}s and MHR_KILL_STRAYS=1, so killing it."
		for p in /proc/[0-9]*; do
			if is_java "$p"; then kill -9 "${p#/proc/}" 2>/dev/null || true; fi
		done
		# Killing is not the same as gone: the kill can have missed, and a JVM takes a moment to
		# die. Nothing may run until the pod actually looks clear.
		tries=0
		while [ "$tries" -lt 10 ]; do
			sleep 1
			tries=$((tries + 1))
			if [ "$(jvms)" = 0 ]; then break; fi
		done
		if [ "$(jvms)" != 0 ]; then
			cat >&2 <<MSG
Killed the leftover JVMs but the pod does not look clear afterwards, so this run stops here.
Something is restarting them. Have a look:

  kubectl -n $ns exec deploy/mhr-gametest -- ps -ef
MSG
			exit 1
		fi
		echo "The pod is clear."
	fi
fi
EOF
}

# The whole run — the stray check, the source install, gradle, and collecting the artifacts — is
# one script in one `kubectl exec`, so the pod is ours from the first of those to the last. The
# tarball goes in before the lock and the artifacts come out after it, neither of which touches
# anything shared.
cmd_gametest() {
	local pod artifacts status=0
	pod="$(require_gametest_pod)"
	stage_source "$pod" "$GAMETEST_AS_USER"
	# Named by the pod, like everything else that lands on the shared volume: our pid means nothing
	# to the other workers writing to it.
	artifacts="$(</dev/null stage_in_pod "$pod" "$GAMETEST_AS_USER" .tar)"

	run_locked "$pod" gametest "$GAMETEST_AS_USER" <<EOF || status=$?
set -euo pipefail
$(remote_clear_strays)
$(remote_install_source "$GAMETEST_WORKSPACE" "$STAGED_TARBALL")
$(gametest_runner "${@:-test runGameTest runClientGameTest}")
$(remote_pack_artifacts "$artifacts")
EOF

	gametest_fetch "$pod" "$artifacts"

	if [[ $status -eq 0 ]]; then
		echo "PASS — gameplay verification green. Artifacts: $GAMETEST_ARTIFACTS"
	else
		echo "FAIL — gameplay verification red (exit $status). Artifacts: $GAMETEST_ARTIFACTS" >&2
	fi
	return $status
}

# Out of the pod after the lock has gone. The tarball is outside the source tree, so the next run
# wiping that tree cannot pull it out from under this copy.
gametest_fetch() {
	local pod="$1" tarball="$2"
	rm -rf "$GAMETEST_ARTIFACTS"
	mkdir -p "$GAMETEST_ARTIFACTS"
	kubectl -n "$NS" exec "$pod" -- $GAMETEST_AS_USER bash -c \
		"cat '$tarball' 2>/dev/null; rm -f '$tarball'" \
		| tar -C "$GAMETEST_ARTIFACTS" -xf - 2>/dev/null || true
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
	sync) cmd_build_pod sync ;;
	build) cmd_build_pod sync build ;;
	deploy) cmd_build_pod deploy ;;
	go) cmd_build_pod sync build deploy ;;
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
