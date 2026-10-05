test: build

install-system-dependencies:
	@echo "Installing system dependencies"

build: install-system-dependencies
	@echo "Build java parser based consumer"
	# clean: the runner reuses one checkout across branches, and delombok output left in
	# target/generated-sources from a previous branch is otherwise compiled against this pom.
	mvn clean package -DskipTests
	# Create a temporary filename in /tmp directory
	touch tmp_jar
	# Create classpath string of dependencies from the local repository to a file
	mvn -Dmdep.outputFile=tmp_jar dependency:build-classpath