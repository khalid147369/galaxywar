FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/install/odyssey-game-server/ ./
ENV PORT=8080
EXPOSE 8080
CMD ["bin/odyssey-game-server"]
