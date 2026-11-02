"""Prepare a draft with the shared Java preparer, then publish verified bytes."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlencode
import zipfile


WORK = Path("release-work")
REPO = os.environ.get("GITHUB_REPOSITORY", "")
MAX_ASSET = 2 * 1024**3 - 1


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version(tag):
    require(isinstance(tag, str) and re.fullmatch(r"v?[0-9]+\.[0-9]+\.[0-9]+", tag),
            "Use a numeric release tag, for example 1.0.2 or v1.0.2")
    return tuple(int(part) for part in tag.removeprefix("v").split("."))


def api(endpoint, *, method="GET", body=None, output=None, upload=None):
    command = ["gh", "api", "--hostname", "github.com", "--method", method,
               "-H", "X-GitHub-Api-Version: 2022-11-28"]
    command += ["-H", "Accept: application/octet-stream" if output
                else "Accept: application/vnd.github+json"]
    data = None
    if body is not None:
        command += ["--input", "-"]
        data = json.dumps(body).encode()
    if upload is not None:
        command += ["-H", "Content-Type: application/octet-stream", "--input", str(upload)]
    command.append(endpoint)
    result = subprocess.run(command, input=data, stdout=output or subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=900, check=False)
    require(result.returncode == 0,
            f"GitHub API request failed ({method}): " + result.stderr.decode(errors="replace"))
    if output is None and result.stdout.strip():
        return json.loads(result.stdout)
    return None


def releases():
    result = []
    page = 1
    while True:
        batch = api(f"repos/{REPO}/releases?per_page=100&page={page}")
        result.extend(batch)
        if len(batch) < 100:
            return result
        page += 1


def assets(release_id):
    result = []
    page = 1
    while True:
        batch = api(f"repos/{REPO}/releases/{release_id}/assets?per_page=100&page={page}")
        result.extend(batch)
        if len(batch) < 100:
            return result
        page += 1


def release_snapshot(release):
    return {key: release.get(key) for key in
            ("id", "tag_name", "target_commitish", "name", "body", "draft", "prerelease")}


def asset_snapshot(asset):
    return {key: asset.get(key) for key in
            ("id", "name", "size", "digest", "updated_at", "state", "label")}


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(asset, destination):
    require(asset["state"] == "uploaded" and 0 < asset["size"] <= MAX_ASSET,
            "Release asset is incomplete or exceeds the 2 GiB limit")
    with destination.open("wb") as stream:
        api(f"repos/{REPO}/releases/assets/{int(asset['id'])}", output=stream)
    require(destination.stat().st_size == asset["size"], "Incomplete release asset download")
    digest = asset.get("digest")
    if digest:
        require(digest == "sha256:" + sha256(destination), "Release asset SHA-256 mismatch")


def mrpacks(items):
    return [item for item in items if item["name"].lower().endswith(".mrpack")]


def highest_stable(items, target_tag):
    stable = [item for item in items if not item["draft"] and not item["prerelease"]]
    if not stable:
        return None
    # Reject unexpected stable tags instead of silently ignoring a published version.
    previous = max(stable, key=lambda item: version(item["tag_name"]))
    require(version(target_tag) > version(previous["tag_name"]),
            "The draft version must be newer than every published stable release")
    return previous


def read_json_entry(path, name, limit):
    with zipfile.ZipFile(path) as archive:
        entry = archive.getinfo(name)
        require(entry.file_size <= limit, f"Archive JSON is too large: {name}")
        with archive.open(entry) as stream:
            data = stream.read(limit + 1)
        require(len(data) <= limit, f"Archive JSON is too large: {name}")
        return json.loads(data)


def check_archive_version(path, tag):
    index = read_json_entry(path, "modrinth.index.json", 16 * 1024**2)
    require(version(index["versionId"]) == version(tag),
            "Release tag does not match the archive versionId")


def save_receipt(receipt):
    (WORK / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")


def load_receipt():
    receipt = json.loads((WORK / "receipt.json").read_text(encoding="utf-8"))
    require(receipt["repository"] == REPO and receipt["commit"] == os.environ["GITHUB_SHA"]
            and receipt["run_id"] == os.environ["GITHUB_RUN_ID"], "Receipt belongs to another workflow run")
    return receipt


def fetch():
    tag = os.environ["RELEASE_TAG"]
    version(tag)
    WORK.mkdir(exist_ok=False)
    all_releases = releases()
    matches = [item for item in all_releases if item["tag_name"] == tag]
    require(len(matches) == 1, "Save exactly one draft release with the requested tag first")
    draft = matches[0]
    require(draft["draft"] and not draft["prerelease"], "The release must be an unpublished stable draft")
    require(draft["target_commitish"] == os.environ["GITHUB_REF_NAME"],
            "Select the default branch as the draft release target")
    final_name = "Eldervale.mrpack"
    pending_name = final_name + ".prepared"
    items = assets(draft["id"])
    sources = mrpacks(items)
    if not sources:
        # A previous run may have stopped after deleting the source, before renaming the verified upload.
        sources = [item for item in items if item["name"] == pending_name
                   and (item.get("label") or "").startswith("eldervale-updater:sha256:")]
    require(len(sources) == 1, "Attach exactly one full .mrpack export to the draft")
    source = sources[0]
    download(source, WORK / "input.mrpack")
    check_archive_version(WORK / "input.mrpack", tag)
    previous = highest_stable(all_releases, tag)
    previous_asset = None
    if previous:
        previous_assets = mrpacks(assets(previous["id"]))
        require(len(previous_assets) == 1, "Previous stable release must contain exactly one .mrpack")
        previous_asset = previous_assets[0]
        download(previous_asset, WORK / "previous.mrpack")
        check_archive_version(WORK / "previous.mrpack", previous["tag_name"])
    save_receipt({
        "repository": REPO, "commit": os.environ["GITHUB_SHA"], "run_id": os.environ["GITHUB_RUN_ID"],
        "release": release_snapshot(draft), "source": asset_snapshot(source),
        "previous": release_snapshot(previous) if previous else None,
        "previous_asset": asset_snapshot(previous_asset) if previous_asset else None,
        "final_name": final_name, "pending_name": pending_name,
    })
    print("Draft downloaded. Source assets have not been changed.")


def prepare():
    receipt = load_receipt()
    tag = receipt["release"]["tag_name"]
    command = ["java", "-Djava.awt.headless=true", "-jar", "helper/build/libs/updater.jar", "prepare",
               str(WORK / "input.mrpack"), str(WORK / "prepared.mrpack"), tag]
    if receipt["previous"]:
        command.append(str(WORK / "previous.mrpack"))
    subprocess.run(command, check=True, timeout=900)
    prepared = WORK / "prepared.mrpack"
    state = read_json_entry(prepared, "overrides/updater/state.json", 32 * 1024**2)
    require(state["schema"] == 1 and version(state["version"]) == version(tag), "Invalid prepared state.json")
    require(0 < prepared.stat().st_size <= MAX_ASSET, "Prepared archive exceeds the GitHub asset limit")
    receipt.update(sha256=sha256(prepared), size=prepared.stat().st_size, files=len(state["files"]))
    save_receipt(receipt)
    print(f"Prepared {receipt['files']} managed files. SHA-256: {receipt['sha256']}")


def unchanged_draft(receipt):
    current = api(f"repos/{REPO}/releases/{int(receipt['release']['id'])}")
    require(release_snapshot(current) == receipt["release"],
            "Draft metadata changed during preparation. Run the workflow again.")
    return current


def unchanged_previous(receipt):
    current = highest_stable(releases(), receipt["release"]["tag_name"])
    require((release_snapshot(current) if current else None) == receipt["previous"],
            "The published release changed during preparation. Run the workflow again.")
    if current:
        current_assets = mrpacks(assets(current["id"]))
        require(len(current_assets) == 1 and asset_snapshot(current_assets[0]) == receipt["previous_asset"],
                "The previous release archive changed during preparation. Run the workflow again.")


def verify_uploaded(asset, receipt):
    destination = WORK / "uploaded.mrpack"
    download(asset, destination)
    require(destination.stat().st_size == receipt["size"] and sha256(destination) == receipt["sha256"],
            "Uploaded archive differs from the prepared archive; draft was not published")
    destination.unlink()


def publish():
    receipt = load_receipt()
    prepared = WORK / "prepared.mrpack"
    require(prepared.stat().st_size == receipt["size"] and sha256(prepared) == receipt["sha256"],
            "Prepared workflow artifact is damaged")
    unchanged_draft(receipt)
    unchanged_previous(receipt)
    release_id = int(receipt["release"]["id"])
    source = receipt["source"]
    items = assets(release_id)
    require(any(asset_snapshot(item) == source for item in items),
            "Source asset changed during preparation. Run the workflow again.")
    require(all(item["id"] == source["id"] for item in mrpacks(items)),
            "Another .mrpack was attached during preparation")
    label = "eldervale-updater:sha256:" + receipt["sha256"]
    # A retry after renaming can use the already prepared final asset directly.
    already_final = source["name"] == receipt["final_name"] and (
        source.get("label") == label or source.get("digest") == "sha256:" + receipt["sha256"])
    if already_final:
        candidate = source
    else:
        candidates = [item for item in items if item["name"] == receipt["pending_name"]]
        require(len(candidates) <= 1, "Duplicate pending release assets")
        if candidates:
            candidate = candidates[0]
            require(candidate.get("label") == label,
                    "A pending .prepared asset belongs to another export. Remove that asset and retry.")
        else:
            query = urlencode({"name": receipt["pending_name"], "label": label})
            candidate = api(f"https://uploads.github.com/repos/{REPO}/releases/{release_id}/assets?{query}",
                            method="POST", upload=prepared)
    verify_uploaded(candidate, receipt)
    unchanged_draft(receipt)
    unchanged_previous(receipt)
    current_items = assets(release_id)
    require(any(asset_snapshot(item) == source for item in current_items),
            "Source changed while uploading. Run the workflow again.")
    require(all(item["id"] in (source["id"], candidate["id"]) for item in mrpacks(current_items)),
            "Another .mrpack was attached while uploading")
    # Until this point the original export is intact. Only the verified replacement survives below.
    if source["id"] != candidate["id"]:
        api(f"repos/{REPO}/releases/assets/{int(source['id'])}", method="DELETE")
    if candidate["name"] != receipt["final_name"] or candidate.get("label"):
        candidate = api(f"repos/{REPO}/releases/assets/{int(candidate['id'])}", method="PATCH",
                        body={"name": receipt["final_name"], "label": ""})
    final_assets = mrpacks(assets(release_id))
    require(len(final_assets) == 1 and asset_snapshot(final_assets[0]) == asset_snapshot(candidate),
            "Unexpected final release assets; draft was not published")
    unchanged_draft(receipt)
    unchanged_previous(receipt)
    result = api(f"repos/{REPO}/releases/{release_id}", method="PATCH",
                 body={"draft": False, "prerelease": False, "make_latest": "true"})
    require(not result["draft"] and not result["prerelease"], "GitHub did not publish the stable release")
    latest = api(f"repos/{REPO}/releases/latest")
    require(latest["id"] == release_id, "Release was published, but GitHub did not mark it Latest")
    summary = (f"### Eldervale {receipt['release']['tag_name']} published\n\n"
               f"{result['html_url']}\n\n"
               f"Managed files: {receipt['files']}\n\nSHA-256: `{receipt['sha256']}`\n")
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as stream:
        stream.write(summary)
    print("Prepared release published as Latest.")


if __name__ == "__main__":
    try:
        require(REPO == "holmzi/eldervale", "This workflow is configured for holmzi/eldervale")
        require(len(sys.argv) == 2 and sys.argv[1] in ("fetch", "prepare", "publish"),
                "Usage: release.py fetch|prepare|publish")
        {"fetch": fetch, "prepare": prepare, "publish": publish}[sys.argv[1]]()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        # Do not print untrusted release text as a GitHub Actions workflow command.
        print("Release preparation failed: " + str(error).replace("\n", " ").replace("\r", " "), file=sys.stderr)
        sys.exit(1)
