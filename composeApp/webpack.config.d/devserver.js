// SPA routing for the dev server: every path serves index.html (History API routes).
if (config.devServer) {
  config.devServer.historyApiFallback = true;
  config.devServer.port = 4173;
  config.devServer.open = false;
  config.devServer.client = { overlay: { errors: true, warnings: false } };
}
