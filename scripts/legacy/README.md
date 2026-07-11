# Legacy Scripts

These scripts contain Windows-specific paths (`E:/shixi_xiangmu/...`) that are no longer valid.
They are kept here for reference but are not maintained.

| File | Issue |
|------|-------|
| `e2e_demo.ps1` | Hard-coded `$RepoUrl = "file:///E:/..."` default parameter |
| `check_env.ps1` | Hard-coded `$ProjectRoot = "E:/..."` default parameter |

For cross-platform equivalents, use the shell scripts in `../scripts/` or pass the correct
path as a parameter when running these on Windows.
