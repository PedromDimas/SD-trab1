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

rm out.txt

sh test-sd-tp1.sh -image sd1920-trab1-52919-52858 -sleep 2 -log ALL -test 12a  |tee out.txt

sh clean_docker.sh



