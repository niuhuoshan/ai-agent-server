FROM mcr.microsoft.com/playwright:v1.52.0-noble

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev --ignore-scripts
COPY server.js ./

ENV PORT=8787
EXPOSE 8787
USER pwuser
CMD ["node", "server.js"]
