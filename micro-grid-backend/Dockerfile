FROM amazoncorretto:21-alpine3.19
VOLUME /home
COPY target/backend-0.0.1-SNAPSHOT.jar .
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar backend-0.0.1-SNAPSHOT.jar" ]