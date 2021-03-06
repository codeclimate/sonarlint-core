#/bin/bash
#
# ITs need the environment variable 'GITHUB_TOKEN' defined
#

if [ -z "$1" ]; then
  echo "syntax: $0 [DEV|LTS|LATEST_RELEASE]"
  exit 1
fi

set -ex
SQ_VERSION=$1

plugins_min_versions_path=core/src/main/resources/plugins_min_versions.txt

case "$SQ_VERSION" in
  LTS)
    minVersions=$(sed -ne '/^[a-z]*=[0-9.]*$/s/$/;/p' < "$plugins_min_versions_path")
    eval "$minVersions"
    JAVA_VERSION=$java
    PHP_VERSION=$php
    JAVASCRIPT_VERSION=$javascript
    JAVASCRIPT_BUILD_VERSION=2.14
    PYTHON_VERSION=$python
    COBOL_VERSION=$cobol
    ;;
  LATEST_RELEASE)
    JAVA_VERSION=LATEST_RELEASE
    PHP_VERSION=LATEST_RELEASE
    JAVASCRIPT_VERSION=LATEST_RELEASE
    # There was an API change that requires to build with this version
    JAVASCRIPT_BUILD_VERSION=2.20.0.4207
    PYTHON_VERSION=LATEST_RELEASE
    # use old license
    COBOL_VERSION=4.0.1
    ;;
  DEV)
    JAVA_VERSION=LATEST_RELEASE
    PHP_VERSION=LATEST_RELEASE
    JAVASCRIPT_VERSION=LATEST_RELEASE
    # There was an API change that requires to build with this version
    JAVASCRIPT_BUILD_VERSION=2.20.0.4207
    PYTHON_VERSION=LATEST_RELEASE
    # use new license mechanism
    COBOL_VERSION=4.2.0
    ;;
  *)
    echo "fatal: unknown SQ_VERSION value '$SQ_VERSION'"
    exit 1
esac

echo "Running with SQ=$SQ_VERSION JAVA_VERSION=$JAVA_VERSION JAVASCRIPT_VERSION=$JAVASCRIPT_VERSION PHP_VERSION=$PHP_VERSION PYTHON_VERSION=$PYTHON_VERSION COBOL_VERSION=$COBOL_VERSION"

export MAVEN_OPTS=-Xmx1024m

cd its
mvn verify -Pits -Dsonar.runtimeVersion=$SQ_VERSION \
    -DjavaVersion=$JAVA_VERSION \
    -DphpVersion=$PHP_VERSION \
    -DjavascriptVersion=$JAVASCRIPT_VERSION \
    -Djavascript.buildVersion=$JAVASCRIPT_BUILD_VERSION \
    -DpythonVersion=$PYTHON_VERSION \
    -DcobolVersion=$COBOL_VERSION

