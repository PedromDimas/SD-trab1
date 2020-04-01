#!/bin/bash

if [ $EUID != 0 ]; then
    sudo "$0" "$@"
    exit $?
fi

echo "****************************************
**********Compiling your shit!**********
****************************************"

mvn clean
mvn compile
mvn assembly:single
mvn docker:build

echo "****************************************
*************Maven finished!************
****************************************"

docker network create -d bridge sdnet

sh test-sd-tp1.sh -image sd1920-trab1-52919-52858

