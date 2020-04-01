#!/bin/bash

if [ $EUID != 0 ]; then
    sudo "$0" "$@"
    exit $?
fi

echo "y" | docker system prune
echo "clean!"
docker ps -a