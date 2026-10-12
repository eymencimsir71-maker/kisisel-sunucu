FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY Main.java .
ADD https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar gson.jar
RUN mkdir -p classes && javac -cp gson.jar -d classes Main.java
CMD ["java", "-cp", "classes:gson.jar", "Main"]
