package ru.dada.build

/**
 * The directory inside the checkout that a monorepo app builds from.
 *
 * The build-agent sends the app's root_dir as the root_dir job parameter. It
 * becomes the Docker build context and the place where the Dockerfile is looked
 * up, sniffed for a framework and, when absent, generated. The value is spliced
 * into shell paths, so anything that is absolute, climbs out with '..', or
 * carries characters beyond letters, digits, '.', '_', '-' and '/' is refused.
 */
class RootDir {

    static final String REPO_ROOT = '.'

    /**
     * Normalizes a root_dir into a path relative to the checkout.
     *
     * @param raw root_dir as sent by the build-agent or read from git_repos
     * @return '.' for the repo root, the cleaned relative path otherwise, or
     *         null when the value is not a safe relative path
     */
    static String normalize(String raw) {
        String s = (raw ?: '').trim()
        if (s.startsWith('/')) {
            return null
        }
        List<String> segs = []
        for (String seg : s.split('/')) {
            if (seg == '' || seg == '.') {
                continue
            }
            if (seg == '..' || !(seg ==~ /[A-Za-z0-9._-]+/)) {
                return null
            }
            segs.add(seg)
        }
        return segs ? segs.join('/') : REPO_ROOT
    }
}
