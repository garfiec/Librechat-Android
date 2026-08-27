#!/usr/bin/env python3
"""Vendor the third-party web assets the artifact WebViews execute.

The WebView renderers (LaTeX, mermaid, markdown, HTML/React artifacts) used to pull
their JavaScript from public CDNs at render time. Everything they run now ships inside
the app instead. What is vendored, at which version, and why each pin is where it is,
is in scripts/web-assets.json -- read that first; this file only moves bytes.

Two problems this solves, and only one of them is about policy:

  1. F-Droid rejects apps that download executable code without explicit opt-in consent.
     A <script src="https://cdn..."> in a WebView is exactly that.

  2. Unversioned CDN URLs drift in total silence. Nothing here fails to build, fails a
     test, or logs a warning when an upstream package reorganises itself -- it breaks on
     a user's device. Both known instances (marked deleting the `highlight` option in v5,
     @babel/standalone rolling onto a new major) went unnoticed for exactly that reason.

Every download is verified against scripts/web-assets.lock.json, which records the sha256
of each vendored file. --check re-verifies the working tree without touching the network,
so CI can prove nobody hand-edited a vendored blob.

    scripts/vendor-web-assets.py --check       verify tree vs lock, no network
    scripts/vendor-web-assets.py --sync        re-download every pin, rewrite the lock
    scripts/vendor-web-assets.py --outdated    ask npm what newer versions exist
    scripts/vendor-web-assets.py --bump ID V   repin one asset and re-download it
    scripts/vendor-web-assets.py --list        print the registry, check nothing
"""

import argparse
import fnmatch
import hashlib
import io
import json
import os
import shutil
import sys
import tarfile
import urllib.error
import urllib.parse
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(REPO, "scripts", "web-assets.json")
LOCK = os.path.join(REPO, "scripts", "web-assets.lock.json")
UA = {"User-Agent": "vendor-web-assets/1 (+switchboard)"}
TIMEOUT = 180

RED, GREEN, YELLOW, DIM, BOLD, OFF = (
    "\033[31m", "\033[32m", "\033[33m", "\033[2m", "\033[1m", "\033[0m",
) if sys.stdout.isatty() else ("", "", "", "", "", "")


def die(msg):
    print("%serror:%s %s" % (RED, OFF, msg), file=sys.stderr)
    sys.exit(1)


def fetch(url):
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        die("%s -> HTTP %s" % (url, e.code))
    except Exception as e:
        die("%s -> %s" % (url, e))


def load_registry():
    with open(REGISTRY) as f:
        reg = json.load(f)
    seen = set()
    for a in reg["assets"]:
        if a["id"] in seen:
            die("duplicate asset id %r in the registry" % a["id"])
        seen.add(a["id"])
    return reg


def load_lock():
    if not os.path.exists(LOCK):
        return {"version": 1, "assets": {}}
    with open(LOCK) as f:
        return json.load(f)


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def npm_tarball(pkg, version):
    """Fetch and open the official registry tarball for one package version."""
    base = pkg.split("/")[-1]
    url = "https://registry.npmjs.org/%s/-/%s-%s.tgz" % (pkg, base, version)
    return tarfile.open(fileobj=io.BytesIO(fetch(url)), mode="r:gz")


def npm_latest(pkg):
    url = "https://registry.npmjs.org/%s" % urllib.parse.quote(pkg, safe="@")
    data = json.loads(fetch(url))
    tags = data.get("dist-tags", {})
    return tags.get("latest"), sorted(data.get("versions", {}).keys())


def resolve_files(asset):
    """Yield (dest_relative_path, bytes) for every file one asset contributes."""
    src = asset["source"]
    tar = None
    if src["type"] == "npm":
        # Opened lazily: an asset can be entirely url-sourced (tailwind) and never
        # need a tarball, and pulling a 3 MB tgz to read nothing is pure waste.
        needs_tar = any("from" in f for f in asset["files"])
        if needs_tar:
            tar = npm_tarball(src["package"], asset["version"])

    members = {}
    if tar is not None:
        members = {n[len("package/"):]: n for n in tar.getnames() if n.startswith("package/")}

    for spec in asset["files"]:
        if "url" in spec:
            yield spec["to"], fetch(spec["url"])
            continue

        frm, to = spec["from"], spec["to"]
        if "*" in frm:
            # A glob must land in a directory: `dist/fonts/*.woff2` -> `fonts/`.
            if not to.endswith("/"):
                die("%s: glob %r must map to a directory ending in '/'" % (asset["id"], frm))
            matched = sorted(n for n in members if fnmatch.fnmatch(n, frm))
            if not matched:
                die("%s: glob %r matched nothing in %s@%s"
                    % (asset["id"], frm, src.get("package"), asset["version"]))
            for name in matched:
                yield to + os.path.basename(name), tar.extractfile(members[name]).read()
        else:
            if frm not in members:
                die("%s: %r is not in %s@%s -- the package layout changed; "
                    "re-read the registry's pin_reason before repinning"
                    % (asset["id"], frm, src.get("package"), asset["version"]))
            yield to, tar.extractfile(members[frm]).read()


def dest_dir(reg, asset):
    return os.path.join(REPO, reg["dest"], asset["dir"])


def render_manifest_kt(reg, lock):
    """Render the generated file list. Returns (path, text, entry_count).

    iOS needs it at runtime: WKWebView will not load local subresources from a page
    given to loadHTMLString, so the assets have to be copied out of the framework
    bundle into a readable directory before the first render, and the copier has to
    know what to copy. Android reads straight from assets/ and ignores this.

    Returns None when an asset has no lock entry -- --check reports that separately,
    and rendering half a manifest would bury it.
    """
    out = os.path.join(REPO, reg["manifest_kt"])
    entries = []
    digest = hashlib.sha256()
    for asset in reg["assets"]:
        entry = lock["assets"].get(asset["id"])
        if entry is None:
            return None
        for rel in sorted(entry["files"]):
            path = "%s/%s" % (asset["dir"], rel)
            entries.append(path)
            digest.update(("%s %s\n" % (path, entry["files"][rel])).encode())
    fingerprint = digest.hexdigest()[:16]

    body = [
        "// Generated by scripts/vendor-web-assets.py -- do not edit by hand.",
        "// Regenerate with: scripts/vendor-web-assets.py --sync",
        "package %s" % reg["manifest_package"],
        "",
        "/**",
        " * Every vendored web asset, as a path under `files/web/` in compose resources.",
        " *",
        " * iOS copies these out of the framework bundle on first use: WKWebView refuses to",
        " * load local subresources for a page handed to `loadHTMLString`, so the HTML and the",
        " * scripts it references must sit together in one directory it has read access to.",
        " * Android serves them straight from `file:///android_asset/` and never reads this.",
        " */",
        "internal object VendoredWebAssets {",
        "    const val ROOT: String = \"files/web\"",
        "",
        "    /**",
        "     * One real file, used to locate the directory the others sit in. Compose",
        "     * Resources can resolve a URI for a file but not for a directory, so the base",
        "     * URL is derived by resolving this and trimming the tail back off.",
        "     */",
        "    const val ANCHOR: String = \"%s\"" % entries[0],
        "",
        "    /**",
        "     * Digest of every vendored file's content hash.",
        "     *",
        "     * iOS decides whether its copy is current by comparing a stored manifest against",
        "     * this one. The file list alone cannot answer that: the pinned filenames carry no",
        "     * version, so bumping a library to a build that ships the same names leaves the",
        "     * list byte-identical and a stale copy would be kept and executed indefinitely.",
        "     */",
        "    const val FINGERPRINT: String = \"%s\"" % fingerprint,
        "",
        "    val FILES: List<String> = listOf(",
    ]
    body += ["        \"%s\"," % e for e in entries]
    body += [
        "    )",
        "}",
        "",
    ]
    return out, "\n".join(body), len(entries)


def write_manifest_kt(reg, lock):
    rendered = render_manifest_kt(reg, lock)
    if rendered is None:
        die("cannot generate the manifest: an asset has no lock entry -- run --sync")
    out, text, n = rendered
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        f.write(text)
    return out, n


def cmd_sync(reg, only=None):
    lock = load_lock()
    lock.setdefault("assets", {})
    total = 0

    for asset in reg["assets"]:
        if only and asset["id"] != only:
            continue
        d = dest_dir(reg, asset)
        # Wipe first: a repin that drops a file (a renamed dist path, a theme we no
        # longer reference) must not leave the old one behind to be silently served.
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d, exist_ok=True)

        files, size = {}, 0
        for rel, data in resolve_files(asset):
            path = os.path.join(d, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                f.write(data)
            files[rel] = sha256(data)
            size += len(data)

        lock["assets"][asset["id"]] = {
            "version": asset["version"],
            "license": asset["license"],
            "source": asset["source"],
            "bytes": size,
            "files": files,
        }
        total += size
        print("  %s%-16s%s v%-10s %2d files %7.1f KB" %
              (BOLD, asset["id"], OFF, asset["version"], len(files), size / 1024))

    with open(LOCK, "w") as f:
        json.dump(lock, f, indent=2, sort_keys=True)
        f.write("\n")

    out, n = write_manifest_kt(reg, lock)
    print("\n  lock     -> %s" % os.path.relpath(LOCK, REPO))
    print("  manifest -> %s (%d files)" % (os.path.relpath(out, REPO), n))
    print("\n%svendored %.1f MB%s" % (GREEN, total / 1024 / 1024, OFF))


def cmd_check(reg):
    """Verify the working tree against the lock. No network."""
    lock = load_lock()
    if not lock["assets"]:
        die("no lock file -- run: scripts/vendor-web-assets.py --sync")

    problems = []
    for asset in reg["assets"]:
        entry = lock["assets"].get(asset["id"])
        if entry is None:
            problems.append("%s: in the registry but not the lock (run --sync)" % asset["id"])
            continue
        if entry["version"] != asset["version"]:
            problems.append("%s: registry pins v%s, lock has v%s (run --sync)"
                            % (asset["id"], asset["version"], entry["version"]))
        d = dest_dir(reg, asset)
        for rel, want in entry["files"].items():
            path = os.path.join(d, rel)
            if not os.path.exists(path):
                problems.append("%s: missing %s" % (asset["id"], rel))
                continue
            with open(path, "rb") as f:
                got = sha256(f.read())
            if got != want:
                problems.append("%s: %s does not match the lock (hand-edited?)" % (asset["id"], rel))
        # A stray file in a vendored directory would ship and be executable.
        for root, _dirs, names in os.walk(d):
            for n in names:
                rel = os.path.relpath(os.path.join(root, n), d)
                if rel not in entry["files"]:
                    problems.append("%s: %s is not in the lock (untracked vendored file)"
                                    % (asset["id"], rel))

    stale = set(lock["assets"]) - {a["id"] for a in reg["assets"]}
    for s in sorted(stale):
        problems.append("%s: in the lock but not the registry (run --sync)" % s)

    # The generated Kotlin manifest is checked too, because it is the only thing that
    # tells iOS what to copy out of the framework bundle. A stale or hand-edited one
    # hashes nothing, breaks no build and fails no test: the file simply never gets
    # copied and the WebView that needs it renders blank, on iOS only.
    rendered = render_manifest_kt(reg, lock)
    if rendered is not None:
        out, want, _n = rendered
        rel = os.path.relpath(out, REPO)
        if not os.path.exists(out):
            problems.append("%s is missing (run --sync)" % rel)
        else:
            with open(out) as f:
                if f.read() != want:
                    problems.append(
                        "%s does not match the lock -- it is generated; run --sync" % rel)

    for p in problems:
        print("  %s%s%s" % (RED, p, OFF))
    if problems:
        print("\n%s%d problem(s)%s" % (RED, len(problems), OFF))
        return 1
    n = sum(len(e["files"]) for e in lock["assets"].values())
    mb = sum(e["bytes"] for e in lock["assets"].values()) / 1024 / 1024
    print("%sok%s  %d assets, %d files, %.1f MB, all hashes match the lock"
          % (GREEN, OFF, len(lock["assets"]), n, mb))
    return 0


def cmd_outdated(reg):
    """Ask npm what is newer. Advisory only -- read pin_reason before acting."""
    for asset in reg["assets"]:
        src = asset["source"]
        if src["type"] != "npm":
            print("  %-16s v%-10s %sno npm source -- %s%s"
                  % (asset["id"], asset["version"], DIM, src.get("url", "pinned url"), OFF))
            continue
        latest, _versions = npm_latest(src["package"])
        if latest == asset["version"]:
            print("  %-16s v%-10s %sup to date%s" % (asset["id"], asset["version"], DIM, OFF))
        else:
            print("  %-16s v%-10s %s-> v%s available%s"
                  % (asset["id"], asset["version"], YELLOW, latest, OFF))
            print("      %s%s%s" % (DIM, asset["pin_reason"], OFF))


def cmd_list(reg):
    for asset in reg["assets"]:
        src = asset["source"]
        origin = src.get("package") or src.get("url")
        print("%s%-16s%s v%-10s %-13s %s" %
              (BOLD, asset["id"], OFF, asset["version"], asset["license"], origin))
        print("    %s" % asset["why"])
        for u in asset["used_by"]:
            print("      %s%s%s" % (DIM, u, OFF))


def cmd_bump(reg, asset_id, version):
    asset = next((a for a in reg["assets"] if a["id"] == asset_id), None)
    if asset is None:
        die("unknown asset %r -- known: %s"
            % (asset_id, ", ".join(a["id"] for a in reg["assets"])))
    if asset["source"]["type"] != "npm":
        die("%s is pinned to a URL, not an npm package. Repin it by editing "
            "scripts/web-assets.json (both the version and every file url), then --sync."
            % asset_id)

    print("%s%s%s v%s -> v%s" % (BOLD, asset_id, OFF, asset["version"], version))
    print("%s%s%s\n" % (YELLOW, asset["pin_reason"], OFF))

    # Edited as text, not round-tripped through json.dump: the registry is mostly prose
    # (every entry carries a pin_reason worth reading) and reserialising it would reflow
    # the whole file, burying a one-word change in a hundred lines of formatting churn.
    with open(REGISTRY) as f:
        raw = f.read()
    anchor = '"id": "%s"' % asset_id
    at = raw.index(anchor)
    version_at = raw.index('"version":', at)
    end = raw.index("\n", version_at)
    old_line = raw[version_at:end]
    new_line = '"version": "%s",' % version
    if not old_line.rstrip().endswith(","):
        new_line = new_line.rstrip(",")
    raw = raw[:version_at] + new_line + raw[end:]
    with open(REGISTRY, "w") as f:
        f.write(raw)

    reg = load_registry()
    cmd_sync(reg, only=asset_id)
    print("\n%sRepinned. This is not done until a device renders it.%s" % (YELLOW, OFF))


def main():
    p = argparse.ArgumentParser(
        description="Vendor the web assets the artifact WebViews execute.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Pins and their rationale live in scripts/web-assets.json.",
    )
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--check", action="store_true", help="verify the tree against the lock (no network)")
    g.add_argument("--sync", action="store_true", help="re-download every pin and rewrite the lock")
    g.add_argument("--outdated", action="store_true", help="ask npm what newer versions exist")
    g.add_argument("--list", action="store_true", help="print the registry")
    g.add_argument("--bump", nargs=2, metavar=("ID", "VERSION"), help="repin one asset and re-download")
    args = p.parse_args()

    reg = load_registry()
    if args.check:
        sys.exit(cmd_check(reg))
    elif args.sync:
        cmd_sync(reg)
    elif args.outdated:
        cmd_outdated(reg)
    elif args.list:
        cmd_list(reg)
    elif args.bump:
        cmd_bump(reg, args.bump[0], args.bump[1])


if __name__ == "__main__":
    main()
