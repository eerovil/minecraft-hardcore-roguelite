# The image the mhr-gametest pod runs: the build image plus a virtual display and a software
# OpenGL driver, because the client GameTests start a real Minecraft client.
#
# These packages used to be apt-get'd on every pod start. That cost 1-2 minutes per start, needed
# Debian's mirrors to be up at exactly that moment, and meant the test environment was whatever
# apt resolved that day. Baking them freezes the environment at image build time instead.
#
# Build and load it with:
#   scripts/dev.sh image
# which prints the command to run on the Mac. There is no registry: the image is imported straight
# into the cluster node's containerd. See docs/dev-environment.md#the-gametest-image.
#
# Must be built for linux/arm64 — the only node is Apple Silicon.

# JDK 25, not 21: Fabric Loom 1.18 refuses to run on anything older, even though the mod itself
# is compiled for Java 21 (see build.gradle). Same base as the build pod, so the two pods agree
# on the toolchain.
FROM gradle:jdk25

# Root, not gradle: the pod's startup script chowns directories on the shared volume, and
# scripts/dev.sh drops back to uid 1000 with setpriv before running Gradle.
USER root

RUN set -eux; \
	export DEBIAN_FRONTEND=noninteractive; \
	apt-get update -qq; \
	apt-get install -y -qq --no-install-recommends \
		xvfb x11-utils mesa-utils \
		libgl1-mesa-dri libglx-mesa0 libglu1-mesa libegl-mesa0 \
		libx11-6 libxext6 libxrender1 libxrandr2 libxi6 libxcursor1 \
		libxinerama1 libxfixes3 libxkbcommon0 libasound2t64; \
	rm -rf /var/lib/apt/lists/*
