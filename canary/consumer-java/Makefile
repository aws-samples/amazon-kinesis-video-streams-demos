test: build

install-system-dependencies:
	@echo "Installing system dependencies"

build: install-system-dependencies
	@echo "Build java parser based consumer"
	# 'clean' is load-bearing, not hygiene. pom.xml points <sourceDirectory> at
	# target/generated-sources/delombok, and delombok only ever ADDS to that
	# directory -- it never prunes a file that has left src/main/java. target/ is
	# gitignored, so the runner's 'git checkout -f' + 'git reset --hard' cannot
	# clear it either, and every job on a node shares one repo dir regardless of
	# branch. Without clean, a single build of a branch that has an extra source
	# file poisons that node for every other branch permanently: the orphan stays
	# in the compiler's source root and fails to resolve against the freshly
	# delomboked classes. That is exactly what took every gamma consumer build
	# down from 2026-08-31 to 2026-09-08 (SoakStreamVerifier.java, added on
	# twcc-canary, calling runVerifyScript/probeDecodable which no other branch
	# has). The runner already skips maven entirely when the commit is unchanged,
	# so clean only costs a full recompile when the branch actually moved --
	# precisely when staleness matters.
	mvn clean package -DskipTests
	# Create a temporary filename in /tmp directory
	touch tmp_jar
	# Create classpath string of dependencies from the local repository to a file
	mvn -Dmdep.outputFile=tmp_jar dependency:build-classpath