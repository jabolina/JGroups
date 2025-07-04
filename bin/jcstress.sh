#!/bin/sh

# Invokes the JCStress built with `mvn clean install -Pjcstress`
# The `-h` will guide about the available options for the test.

# By default, only `./bin/jcstress.sh` is enough to verify the tests.
# For long and thorough test execution, utilize the `-m tough` or `-m stress`. The latter can take a day to complete.
# To configure the tests with custom environment properties, utilize `-jvmArgsPrepend`, for example, `-jvmArgsPrepend "-Dorg.jgroups.jcstress.mode=bq"`

java -jar target/concurrency-test.jar -r target $*
