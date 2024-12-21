#!/bin/bash

if [[ "$1" = "--local" ]]; then local=true; fi

if ! [[ ${local} ]]; then
  cd gradle-plugin || exit
  ./gradlew publish --no-configuration-cache
  cd ..
  ./gradlew publish --no-configuration-cache
else
  cd gradle-plugin || exit
  ./gradlew publishToMavenLocal
  cd ..
  ./gradlew publishToMavenLocal
fi