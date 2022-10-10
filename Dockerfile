FROM maven:3.6.0-jdk-8-slim AS build
WORKDIR /home/app
RUN apt-get update -y
RUN apt-get install -y python
COPY . /home/app
RUN mvn -am clean package -DskipTests

ENTRYPOINT ["bin/ycsb"]