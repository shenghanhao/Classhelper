# Safe GitHub publication / 安全发布到GitHub

Nothing is uploaded automatically. The public snapshot has independent history; private main and old branches must stay local. Review the source archive and APK before uploading.

## 1. Repository / 建立仓库

Create an empty GitHub repository under an account you control. Do not initialize it with another README/license. If you want identity privacy, choose an appropriate public username and enable GitHub's private commit-email option; a public account/repository still exposes its owner. Do not upload your entire local project folder or `.git` directory.

中文：先在GitHub创建自己控制的空仓库，不自动添加README/许可。注意GitHub用户名/主页本身也会公开身份；可使用GitHub提供的隐私提交邮箱。不要拖拽整个本机目录或上传`.git`。

## 2. Push only the public branch / 只推公开分支

Run from this repository after confirming `publish` is the clean public branch. Replace **OWNER** and **REPO** with your chosen values:

```sh
python3 tools/audit_public_tree.py --ref publish --require-root
git log --format=fuller publish
git remote add public https://github.com/OWNER/REPO.git
git -c push.followTags=false push public publish:main
```

The remote's `main` is the public branch; it is **not** your local private `main`. Never use `git push --all`, `--mirror`, `--tags`, force-push, or push `codex/publish-preparation`. These may leak private history/tags or overwrite collaborators. Push only an explicitly reviewed public ref. Do not merge public/community code back into private main without your separate approval.

中文：上面把本地`publish`推为新GitHub仓库的`main`，**不推本地私人main**。严禁`--all`、`--mirror`、`--tags`或force push；不要推准备分支。没有你的另行批准，不把社区改动合回自用版。

If you prefer a ZIP, use the supplied **source ZIP generated from `git archive publish`**, not a Finder archive of the work folder (which may include ignored private files). GitHub's browser upload has a per-file size limit; the APK belongs in Releases, not the source tree.

## 3. Contributions require your approval / 贡献必须经你批准

Public visibility does not give strangers write access. Contributors should fork and open PRs. Do not grant write/admin access merely to allow contributions. Enable Issues/Discussions if desired, and follow `CONTRIBUTING.md`.

In GitHub **Settings → Rules / Branches**, protect the public `main` and release tags: require PR reviews, dismiss stale approvals, resolve conversations, block force pushes/deletions and bypass where supported. Keep merge/release authority restricted to you. Do not enable auto-merge or automatic release/deployment. A PR itself does not modify the maintained branch. Exact protection features depend on GitHub account/repository settings.

If you later need CODEOWNERS, add your actual chosen public account as owner and require code-owner review; a placeholder is not enforcement. Your own PR cannot be self-approved as an independent required reviewer, so choose a protection policy that matches your maintainer workflow. Protect private main separately; public rules do not protect a different local repository.

中文：公开不等于别人能直接修改。贡献者Fork后发PR，不必授予写权限。设置分支/标签保护，要求审查、禁止强推/删除，不开自动合并/自动发布；只有你授权才接受修改。CODEOWNERS要填真实公开账号才有效，分支保护不是由README文字自动启用的。

## 4. Publish the test APK / 上传APK

1. In GitHub, open **Releases → Draft a new release**.
2. Select the **public** default branch. Create a version tag such as `v0.1.0-test.1` on that branch, not on private history.
3. Title it **Class Memory — experimental test build**. State Android10+, arm64, selected model, Debug/test signing and known limitations.
4. Attach the provided APK and SHA-256 checksum as **release assets**. Do not add APKs to Git commits; do not attach keystores, raw recordings, personal transcripts or local backups.
5. Review licenses/notices and permission warning; manually publish when satisfied. No upload or publication was performed by this preparation task.

中文：在Releases新建发行，标签指向公开分支，附上APK和SHA-256，不把APK提交进Git。写明Android10+、arm64、X-ASR、Debug测试签名及实验限制。检查资料不含私人内容后，由你手动发布。

## 5. Future maintenance / 后续维护

After first publication, work on branches based on `publish`, not by resetting it to private main. Review any cherry-picked private changes for personal data before accepting them. Only push the explicit public ref. Re-run the read-only audit without `--require-root` once legitimate public follow-up commits exist. Keep a private release-signing key if you establish a production update channel; do not rotate keys casually or share them with contributors.

No source scan or disclaimer guarantees freedom from privacy, copyright, security or legal risks. The supplied test APK's language persistence and visual layout still need device checks; building is not device acceptance.

## Official references / 官方说明

- [Sensitive data and Git history](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)
- [GitHub file-size limits](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github)
- [Protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
- [Managing releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
