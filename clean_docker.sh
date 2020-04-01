#!/bin/bash

if [ $EUID != 0 ]; then
    sudo "$0" "$@"
    exit $?
fi

docker stop $(docker ps -a -q)
docker rm $(docker ps -a -q)
echo "y" | docker system prune
docker ps -a