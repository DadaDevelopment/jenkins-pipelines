package ru.dada.build

/**
 * Dockerfile template for the static / SPA family (react, vite, static).
 *
 * The nginx image serves whatever the build stage leaves in /out. A repo that
 * ships a package.json is installed and built as before; a repo that ships none
 * (a hand-written index.html, which is exactly what the `static` detection
 * fires on) is copied straight through instead of dying on `npm install`.
 */
class StaticSite {

    static final List<String> TEMPLATE_FRAMEWORKS = ['react', 'vite', 'static']

    static final int DEFAULT_PORT = 80

    /**
     * Renders the Dockerfile.
     *
     * @param port    app port, 0 means default
     * @param install install command, blank means npm install
     * @param build   build command, blank means npm run build
     * @param output  build output dir relative to the repo root, blank means dist
     */
    static String dockerfile(int port, String install, String build, String output) {
        int p = port ?: DEFAULT_PORT
        String outDir = (output ?: '').trim() ?: 'dist'
        String installCmd = (install ?: '').trim() ?: 'npm install'
        String buildCmd = (build ?: '').trim() ?: 'npm run build'
        String assemble = "if [ -f package.json ]; then ${installCmd} && ${buildCmd} && cp -r \"${outDir}\" /out;" +
                ' else echo "no package.json - serving the repository as static files"; mkdir -p /out && cp -r . /out; fi'

        return """FROM node:20-bookworm AS build
WORKDIR /app
RUN corepack enable
COPY . .
RUN ${assemble}
FROM nginx:alpine
COPY --from=build /out /usr/share/nginx/html
RUN printf 'server {\\n    listen ${p};\\n    location / {\\n        root /usr/share/nginx/html;\\n        try_files \$uri \$uri/ /index.html;\\n    }\\n}\\n' > /etc/nginx/conf.d/default.conf
EXPOSE ${p}
"""
    }
}
