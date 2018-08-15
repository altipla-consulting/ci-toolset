#!/bin/bash

set -eu


function run {
  echo
  echo "+ $*"
  $*
}


function configure-google-cloud {
  PATH=/root/google-cloud-sdk/bin:$PATH

  if [ -z ${GOOGLE_PROJECT-} ]; then
    echo "Cannot configure Google Cloud without the global GOOGLE_PROJECT variable"
    exit 1
  fi

  run gcloud config set core/project $GOOGLE_PROJECT
  run gcloud config set core/disable_prompts True
  if [ ! -n ${GOOGLE_CLUSTER-} ] && [ ! -n ${GOOGLE_ZONE-} ]; then
    run gcloud config set container/cluster $GOOGLE_CLUSTER
    run gcloud config set compute/zone $GOOGLE_ZONE
    run gcloud container clusters get-credentials $GOOGLE_CLUSTER
  fi
}


func docker-build-autotag {
  run docker build -t container -f $2 $3
  
  HASH=$(docker image inspect container -f '{{.Id}}' | cut -d ':' -f 2)
  VERSION=${HASH:0:11}

  run docker tag container $1:latest
  run docker tag container $1:$VERSION
}