#!/usr/bin/env bash

if [[ -L "$0" ]]; then
    dir=$(dirname `readlink $0`)
else
    dir=$(dirname $0)
fi

module_path=$(cat $dir/classpath):$dir/target/classes
JAVA=$SDKMAN_CANDIDATES_DIR/java/18.0.1.1-open/bin/java
$JAVA -XX:MaxRAMPercentage=5.0 \
      -XX:+UseZGC \
      --enable-preview \
      -p $module_path \
      -m com.github.andirady.jlangd/com.github.andirady.jlangd.Main $@
