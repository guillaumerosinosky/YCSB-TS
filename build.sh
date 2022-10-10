IMAGE=${DOCKER_IMAGE:-grosinosky/ycsb-ts}
docker build . -t $IMAGE
docker push $IMAGE
