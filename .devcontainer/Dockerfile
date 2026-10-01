FROM mcr.microsoft.com/devcontainers/base:noble
RUN apt-get update && apt-get install -y python3 python3-pip python3-venv python-is-python3 git openjdk-11-jdk-headless openjdk-17-jdk-headless openjdk-17-jre openjdk-17-jdk android-sdk wget curl xvfb



# Copy Android SDK licenses
COPY ./docker_files/licenses /usr/lib/android-sdk/licenses
RUN chown -R vscode:vscode /usr/lib/android-sdk

ENV JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64
ENV ANDROID_HOME=/usr/lib/android-sdk

USER vscode
WORKDIR /home/vscode

# NullAway is consumed as a released dependency (com.uber.nullaway:nullaway, see
# gradle/dependencies.gradle). Since NullAway 0.14.0 it serializes errors as errors.xml
# (serialization version 4, uber/NullAway#1322), which the annotator consumes directly, so no
# custom NullAway fork needs to be built here anymore.

CMD ["bash"]