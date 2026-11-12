# Android build toolchain for hosts without a JDK or SDK (and without KVM, so
# no emulator here: UI is verified with Robolectric/Roborazzi on the JVM).
FROM eclipse-temurin:21-jdk-noble
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl git fontconfig fonts-noto-cjk && rm -rf /var/lib/apt/lists/*
RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd /tmp \
 && curl -sSLo tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
 && unzip -q tools.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest && rm tools.zip
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
RUN yes | sdkmanager --licenses >/dev/null && sdkmanager "platform-tools" "platforms;android-37.0" >/dev/null
RUN chmod -R a+rwX $ANDROID_HOME
