FROM node:20-bookworm AS frontend
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY index.html vite.config.ts tsconfig.json ./
COPY src ./src
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src/main ./src/main
COPY --from=frontend /app/dist ./src/main/resources/static
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV MEMORIA_DATA_DIR=/data
ENV MEMORIA_DEFAULT_TAKEOUT=/imports/Takeout
ENV MEMORIA_IMPORT_ROOTS=/imports
EXPOSE 8787
VOLUME ["/data", "/imports"]
COPY --from=backend /app/target/memoria-0.1.0.jar /app/memoria.jar
ENTRYPOINT ["java", "-jar", "/app/memoria.jar"]
