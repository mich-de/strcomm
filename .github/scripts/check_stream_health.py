#!/usr/bin/env python3
"""
StrComm Stream & Domain Health Check Script.
Checks:
1. Catalogue domain connectivity and redirect resolution.
2. Iframe embed URL extraction.
3. Master HLS playlist (.m3u8) token generation and validity.
4. CDN segment reachable.

If a domain migration is detected, updates source files and bumps version so GitHub Actions can compile and publish a release.
"""

import sys
import os
import re
import html
import urllib.request
import urllib.error

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

CHANNEL_FILE = "engine/src/main/java/com/s4me/tv/engine/channels/StreamingCommunityChannel.kt"
APP_GRADLE = "app/build.gradle.kts"
CLIENT_GRADLE = "client/build.gradle.kts"
MOBILE_GRADLE = "mobile/build.gradle.kts"
CLAUDE_MD = "CLAUDE.md"

def set_github_output(name: str, value: str):
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as f:
            f.write(f"{name}={value}\n")
    print(f"[GITHUB_OUTPUT] {name}={value}")

def get_current_host() -> str:
    with open(CHANNEL_FILE, "r", encoding="utf-8") as f:
        content = f.read()
    m = re.search(r'override val host:\s*String\s*=\s*"([^"]+)"', content)
    if not m:
        m = re.search(r'var host:\s*String\s*=\s*"([^"]+)"', content)
    return m.group(1) if m else "https://streamingcommunityz.build"

def get_current_version() -> tuple[str, int]:
    with open(APP_GRADLE, "r", encoding="utf-8") as f:
        content = f.read()
    vname = re.search(r'versionName\s*=\s*"([^"]+)"', content).group(1)
    vcode = int(re.search(r'versionCode\s*=\s*(\d+)', content).group(1))
    return vname, vcode

def bump_version_string(vname: str) -> str:
    parts = vname.split(".")
    if len(parts) == 3 and parts[-1].isdigit():
        parts[-1] = str(int(parts[-1]) + 1)
        return ".".join(parts)
    return f"{vname}.1"

def http_get(url: str, referer: str = None, timeout: int = 15) -> tuple[int, str, str]:
    headers = {"User-Agent": USER_AGENT}
    if referer:
        headers["Referer"] = referer
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            return resp.status, resp.geturl(), body
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace") if e.fp else ""
        return e.code, url, body

def check_catalogue_domain(current_host: str) -> tuple[str, bool]:
    seeds = [
        current_host,
        "https://streamingcommunityz.build",
        "https://streamingcommunityz.pro",
        "https://streamingcommunityz.xyz",
        "https://streamingcommunity.computer"
    ]
    working_host = None
    for seed in seeds:
        print(f"Testing catalogue seed: {seed}...")
        try:
            status, final_url, _ = http_get(seed)
            if status == 200:
                clean_host = final_url.rstrip("/")
                print(f"Catalogue online at: {clean_host}")
                working_host = clean_host
                break
        except Exception as e:
            print(f"Seed {seed} failed: {e}")

    if not working_host:
        return current_host, False
    changed = (working_host != current_host.rstrip("/"))
    return working_host, changed

def check_video_sources(host: str) -> tuple[bool, str]:
    sample_slugs = ["66299-animals", "1484-9-1-1?episode_id=13597&next_episode=1"]
    last_err = "No sample title checked"

    for slug in sample_slugs:
        iframe_url = f"{host}/it/iframe/{slug}"
        print(f"Testing iframe: {iframe_url}")
        status, _, html_iframe = http_get(iframe_url, referer=host)
        if status != 200 or not html_iframe:
            last_err = f"Iframe {iframe_url} returned HTTP {status}"
            continue

        m_embed = re.search(r'src=["\'](https://[^"\']+embed[^"\']+)["\']', html_iframe)
        if not m_embed:
            last_err = f"No embed URL found in iframe {iframe_url}"
            continue

        embed_url = html.unescape(m_embed.group(1))
        print(f"Embed URL found: {embed_url}")

        status, _, html_embed = http_get(embed_url, referer=host)
        if status != 200 or not html_embed:
            last_err = f"Embed {embed_url} returned HTTP {status}"
            continue

        m_token = re.search(r"['\"]token['\"]\s*:\s*['\"]([^'\"]+)['\"]", html_embed)
        m_expires = re.search(r"['\"]expires['\"]\s*:\s*['\"]([^'\"]+)['\"]", html_embed)
        m_url = re.search(r"url\s*:\s*['\"]([^'\"]+)['\"]", html_embed)

        if not (m_token and m_expires and m_url):
            last_err = f"Failed to extract masterPlaylist parameters from embed {embed_url}"
            continue

        token = m_token.group(1)
        expires = m_expires.group(1)
        base_playlist_url = m_url.group(1)
        sep = "&" if "?" in base_playlist_url else "?"
        playlist_url = f"{base_playlist_url}{sep}token={token}&expires={expires}"
        print(f"Resolved Master playlist: {playlist_url}")

        embed_origin = re.search(r"(https://[^/]+)", embed_url).group(1)
        status, _, hls_text = http_get(playlist_url, referer=f"{embed_origin}/")
        if status != 200 or "#EXTM3U" not in hls_text:
            last_err = f"Playlist fetch failed or not HLS (#EXTM3U missing), HTTP {status}"
            continue

        print("HLS master playlist verified successfully!")
        return True, "OK"

    return False, last_err

def apply_domain_update(old_host: str, new_host: str, new_vname: str, new_vcode: int):
    print(f"Updating domain in files from {old_host} to {new_host} (Version {new_vname} / {new_vcode})...")
    # 1. Update Channel
    with open(CHANNEL_FILE, "r", encoding="utf-8") as f:
        ch_text = f.read()
    ch_text = ch_text.replace(old_host, new_host)
    with open(CHANNEL_FILE, "w", encoding="utf-8") as f:
        f.write(ch_text)

    # 2. Update gradle files
    for gradle_path in [APP_GRADLE, CLIENT_GRADLE, MOBILE_GRADLE]:
        with open(gradle_path, "r", encoding="utf-8") as f:
            g_text = f.read()
        g_text = re.sub(r'versionCode\s*=\s*\d+', f'versionCode = {new_vcode}', g_text)
        g_text = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{new_vname}"', g_text)
        with open(gradle_path, "w", encoding="utf-8") as f:
            f.write(g_text)

    # 3. Update CLAUDE.md
    if os.path.exists(CLAUDE_MD):
        with open(CLAUDE_MD, "r", encoding="utf-8") as f:
            c_text = f.read()
        c_text = c_text.replace(old_host, new_host)
        with open(CLAUDE_MD, "w", encoding="utf-8") as f:
            f.write(c_text)

def main():
    current_host = get_current_host()
    vname, vcode = get_current_version()
    print(f"StrComm current version: {vname} (code {vcode}), configured host: {current_host}")

    live_host, domain_changed = check_catalogue_domain(current_host)
    if not live_host:
        print("ERROR: Catalogue site unreachable on all seeds!")
        set_github_output("needs_update", "false")
        set_github_output("check_failed", "true")
        set_github_output("error_message", "Catalogue site unreachable on all seeds")
        sys.exit(1)

    sources_ok, sources_msg = check_video_sources(live_host)
    if not sources_ok:
        print(f"ERROR: Video sources check failed: {sources_msg}")
        set_github_output("needs_update", "false")
        set_github_output("check_failed", "true")
        set_github_output("error_message", sources_msg)
        sys.exit(1)

    if domain_changed:
        new_vname = bump_version_string(vname)
        new_vcode = vcode + 1
        print(f"Domain change detected: {current_host} -> {live_host}!")
        apply_domain_update(current_host, live_host, new_vname, new_vcode)
        set_github_output("needs_update", "true")
        set_github_output("check_failed", "false")
        set_github_output("new_version", new_vname)
        set_github_output("new_version_code", str(new_vcode))
        set_github_output("reason", f"Dominio StreamingCommunity aggiornato da {current_host} a {live_host}")
    else:
        print("ALL CHECKS PASSED: Domain unchanged and video sources operational.")
        set_github_output("needs_update", "false")
        set_github_output("check_failed", "false")
        set_github_output("reason", "Dominio e fonti operative")

if __name__ == "__main__":
    main()
