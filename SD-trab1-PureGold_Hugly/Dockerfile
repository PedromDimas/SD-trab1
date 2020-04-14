# base image - an image with openjdk  8
FROM nunopreguica/sd1920tpbase

# working directory inside docker image
WORKDIR /home/sd

# copy the jar created by assembly to the docker image
COPY target/*jar-with-dependencies.jar sd1920.jar

# copy the file of properties to the docker image
COPY messages.props messages.props

# run Discovery when starting the docker image
CMD ["java", "-cp", "/home/sd/sd1920.jar", "sd1920.trab1.server.MessageServer"]
