# ⚙️ Etapa 1: Build da aplicação
FROM gradle:8.5-jdk17 AS builder

WORKDIR /app

# Copia arquivos do projeto
COPY . .

# Executa o build (gera o .jar)
RUN gradle build -x test

# 🐳 Etapa 2: Imagem final com JDK 17 minimalista
FROM eclipse-temurin:17-jdk-alpine

# Define diretório de trabalho
WORKDIR /app

# Copia o .jar do build
COPY --from=builder /app/build/libs/*.jar app.jar

# Define variáveis de ambiente padrão (override no Docker Run ou Render)
ENV SERVER_PORT=8081

# Expõe a porta configurada (Render usa isso)
EXPOSE ${SERVER_PORT}

# Comando para iniciar a aplicação
ENTRYPOINT ["java", "-jar", "app.jar"]
