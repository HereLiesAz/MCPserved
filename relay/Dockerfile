FROM node:20-alpine AS build
WORKDIR /app
COPY package.json tsconfig.json ./
RUN npm install
COPY src ./src
RUN npm run build

FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY package.json ./
RUN npm install --omit=dev
COPY --from=build /app/dist ./dist

# Off by default; the operator sets these. See README.md.
ENV RELAY_PORT=8787
ENV RELAY_IDLE_TIMEOUT_MS=600000

EXPOSE 8787
CMD ["node", "dist/server.js"]
