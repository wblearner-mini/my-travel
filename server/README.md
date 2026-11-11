# 我的旅游 服务端

App 的最小服务端（Python / FastAPI，SQLite）：验证账号，代替手机调用阿里云百炼（AI 对话、订单截图解析）、DeepSeek Search API（联网搜索）和和风天气，并对每个账号统一限额。只提供 App 需要的几个业务接口，不做通用转发。

- 数据：`data/mytravel.db`，存账号、会话、用量记录和天气缓存，**不存行程**（行程只在手机上）。
- 日志只记录用量（账号编号、类型、耗时、Token 数、估算费用），不记录密钥、消息内容、截图或坐标。

## 配置

上游凭证默认从仓库根目录的 `local.properties` 读取，也可以用环境变量 `MYTRAVEL_CONFIG` 指向另一个 key=value 文件：

| 键 | 用途 |
| --- | --- |
| `DASHSCOPE_API_KEY`、`DASHSCOPE_BASE_URL`、`DASHSCOPE_MODEL` | 阿里云百炼 |
| `DEEPSEEK_API_KEY` | DeepSeek 联网搜索 |
| `QWEATHER_API_HOST`、`QWEATHER_PROJECT_ID`、`QWEATHER_CREDENTIAL_ID`、`QWEATHER_DEVELOPER_ID`、`QWEATHER_PRIVATE_KEY_PATH` | 和风天气（服务端用 Ed25519 私钥签 JWT） |

其他环境变量：`MYTRAVEL_DB`（数据库路径）、`MYTRAVEL_OWNERS`（逗号分隔的账号名，这些账号不计入、也不受所有账号合计的月度费用上限约束）。各项限额见 `mytravel_server/config.py`。

## 运行

```bash
cd server
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
./make_cert.sh          # 自签证书：私钥留在 certs/，公开证书复制进 App（之后重新编译 App）
                        # 服务端不只在本机回环地址上时：LAN_IP=… TAILSCALE_IP=… ./make_cert.sh
.venv/bin/python -m mytravel_server.admin create-user <账号名>   # 按提示输入密码；不开放注册
.venv/bin/python -m mytravel_server --host 127.0.0.1 --port 18443 --cert certs/server.crt --key certs/server.key
```

手机通过 USB 连接时，用 `adb reverse tcp:18443 tcp:18443` 让手机访问自己的 `localhost:18443`（debug 包的默认地址）。

管理命令：`create-user`、`set-password`（同时撤销该账号全部会话）、`list-sessions`、`revoke-session <编号>`、`revoke-all <账号>`、`unlock <账号>`、`disable-user` / `enable-user`、`usage --days 7`。

## 测试

```bash
cd server && .venv/bin/python -m pytest -q tests
```

全部使用模拟上游，不调用真实的百炼、DeepSeek、和风，不产生费用。
