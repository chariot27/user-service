# ⚙️ Etapa 1: Build da aplicação
FROM gradle:8.5-jdk17 AS builder

WORKDIR /app

# Copia todos os arquivos do projeto
COPY . .

# Executa o build (gera o .jar)
RUN gradle bootJar -x test

# 🐳 Etapa 2: Imagem final com JDK leve
FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

# Copia o JAR gerado na etapa anterior
COPY --from=builder /app/build/libs/*.jar app.jar
# Define variáveis de ambiente padrão (override no Docker Run ou Render)
ENV SERVER_PORT=8081

# Expõe a porta configurada (Render usa isso)
EXPOSE ${SERVER_PORT}

# Comando para iniciar a aplicação
ENTRYPOINT ["java", "-jar", "app.jar"]
