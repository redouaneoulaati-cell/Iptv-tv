FROM node:22-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev --no-audit --no-fund
COPY src ./src
COPY sql ./sql
EXPOSE 8080
USER node
CMD ["node", "src/server.js"]
