#!/usr/bin/env python3
"""从 MCC 的 PacketPalette*.cs 生成安卓端需要的 Kotlin 包 ID 表。

MCC 上游为每个 Minecraft 版本维护一份包 ID 表（Protocol/Handlers/PacketPalettes/PacketPalette*.cs）。
安卓端只用到其中很少一部分包（登录、配置阶段、聊天、保活），为了不手工抄表出错，
这里直接从上游 C# 源码里提取这些包的 ID 并生成 Kotlin 常量表。

用法：
    python3 tools/gen_packet_ids.py                 # 使用仓库默认相对路径
    python3 tools/gen_packet_ids.py --mcc-root DIR  # 指定 MCC 仓库根目录
    python3 tools/gen_packet_ids.py --check         # 只校验，不写文件
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PALETTE_DIR = Path("MinecraftClient/Protocol/Handlers/PacketPalettes")

# 需要提取的包：Kotlin 枚举名与 MCC 的 C# 成员名保持一致，便于与上游对照。
WANTED_IN_PLAY = [
    "KeepAlive",
    "Ping",
    "Disconnect",
    "JoinGame",
    "SystemChat",
    "ChatMessage",
    "ProfilelessChatMessage",
    "PluginMessage",
    "StartConfiguration",
    "TabComplete",
    "PlayerInfo",
]

WANTED_OUT_PLAY = [
    "KeepAlive",
    "Pong",
    "ChatMessage",
    "ChatCommand",
    "SignedChatCommand",
    "ClientSettings",
    "PluginMessage",
    "ClientStatus",
    "PlayerSession",
    "AcknowledgeConfiguration",
]

WANTED_IN_CONFIG = [
    "Disconnect",
    "FinishConfiguration",
    "KeepAlive",
    "Ping",
    "KnownDataPacks",
    "CodeOfConduct",
    "CookieRequest",
    "PluginMessage",
]

WANTED_OUT_CONFIG = [
    "ClientInformation",
    "CookieResponse",
    "PluginMessage",
    "FinishConfiguration",
    "KeepAlive",
    "Pong",
    "KnownDataPacks",
    "AcceptCodeOfConduct",
]

# 协议号 -> (调色板类名, 适用版本说明)。映射来源：Protocol/Handlers/PacketType18Handler.cs
PROTOCOL_TO_PALETTE: list[tuple[str, str, str]] = [
    ("766", "PacketPalette1206", "1.20.5 - 1.20.6"),
    ("767", "PacketPalette121", "1.21 - 1.21.1"),
    ("768", "PacketPalette1212", "1.21.2 - 1.21.3"),
    ("769", "PacketPalette1214", "1.21.4"),
    ("770", "PacketPalette1215", "1.21.5"),
    ("771", "PacketPalette1216", "1.21.6"),
    ("772", "PacketPalette1216", "1.21.7 - 1.21.8"),
    ("773", "PacketPalette1219", "1.21.9 - 1.21.10"),
    ("774", "PacketPalette1219", "1.21.11"),
    ("775", "PacketPalette261", "26.1"),
    ("776", "PacketPalette261", "26.2"),
]

ENTRY_RE = re.compile(
    r"\{\s*(0x[0-9A-Fa-f]+|\d+)\s*,\s*"
    r"(PacketTypesIn|PacketTypesOut|ConfigurationPacketTypesIn|ConfigurationPacketTypesOut)"
    r"\.(\w+)\s*\}"
)
DICT_RE = re.compile(r"Dictionary<int,\s*(\w+)>\s+(\w+)\s*=")

DICT_KIND = {
    "PacketTypesIn": "in_play",
    "PacketTypesOut": "out_play",
    "ConfigurationPacketTypesIn": "in_config",
    "ConfigurationPacketTypesOut": "out_config",
}


def parse_palette(path: Path) -> dict[str, dict[str, int]]:
    """解析单个 C# 调色板文件，返回 {包方向: {C# 成员名: ID}}。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    tables: dict[str, dict[str, int]] = {
        "in_play": {},
        "out_play": {},
        "in_config": {},
        "out_config": {},
    }
    current: str | None = None

    for line in text.splitlines():
        dict_match = DICT_RE.search(line)
        if dict_match:
            current = DICT_KIND.get(dict_match.group(1))
            continue

        entry_match = ENTRY_RE.search(line)
        if entry_match and current is not None:
            packet_id = int(entry_match.group(1), 0)
            enum_type = entry_match.group(2)
            member = entry_match.group(3)
            if DICT_KIND.get(enum_type) == current:
                tables[current][member] = packet_id

        if line.strip() == "};":
            current = None

    return tables


def kotlin_hex(value: int) -> str:
    return f"0x{value:02X}"


def emit_map(entries: dict[str, int], wanted: list[str], enum_name: str) -> str:
    lines = []
    for member in wanted:
        if member in entries:
            lines.append(f"        {enum_name}.{member} to {kotlin_hex(entries[member])},")
    if not lines:
        return "        // 该版本不存在这些包"
    return "\n".join(lines)


def generate(mcc_root: Path) -> str:
    palette_dir = mcc_root / PALETTE_DIR
    parsed: dict[str, dict[str, dict[str, int]]] = {}
    for _, palette_name, _ in PROTOCOL_TO_PALETTE:
        if palette_name in parsed:
            continue
        path = palette_dir / f"{palette_name}.cs"
        if not path.is_file():
            raise SystemExit(f"找不到上游调色板文件：{path}")
        parsed[palette_name] = parse_palette(path)

    out: list[str] = []
    out.append("// 本文件由 tools/gen_packet_ids.py 从 MCC 上游 PacketPalette*.cs 自动生成，请勿手工修改。")
    out.append("// 重新生成：python3 tools/gen_packet_ids.py")
    out.append("")
    out.append("package mccandroid.core.protocol")
    out.append("")
    out.append("import mccandroid.core.protocol.PacketIdTables.InConfig")
    out.append("import mccandroid.core.protocol.PacketIdTables.InPlay")
    out.append("import mccandroid.core.protocol.PacketIdTables.OutConfig")
    out.append("import mccandroid.core.protocol.PacketIdTables.OutPlay")
    out.append("")

    for palette_name, tables in parsed.items():
        out.append(f"/** 包 ID 表，来源：MCC {PALETTE_DIR.name}/{palette_name}.cs */")
        out.append(f"internal object {palette_name} : PacketIdTable {{")
        out.append("    override val inPlay: Map<InPlay, Int> = mapOf(")
        out.append(emit_map(tables["in_play"], WANTED_IN_PLAY, "InPlay"))
        out.append("    )")
        out.append("")
        out.append("    override val outPlay: Map<OutPlay, Int> = mapOf(")
        out.append(emit_map(tables["out_play"], WANTED_OUT_PLAY, "OutPlay"))
        out.append("    )")
        out.append("")
        out.append("    override val inConfig: Map<InConfig, Int> = mapOf(")
        out.append(emit_map(tables["in_config"], WANTED_IN_CONFIG, "InConfig"))
        out.append("    )")
        out.append("")
        out.append("    override val outConfig: Map<OutConfig, Int> = mapOf(")
        out.append(emit_map(tables["out_config"], WANTED_OUT_CONFIG, "OutConfig"))
        out.append("    )")
        out.append("}")
        out.append("")

    out.append("/** 协议号 -> 包 ID 表，来源：MCC ProtocolType18Handler.GetTypeHandler() */")
    out.append("internal fun packetIdTableForProtocol(protocol: Int): PacketIdTable = when (protocol) {")
    for protocol, palette_name, comment in PROTOCOL_TO_PALETTE:
        out.append(f"    {protocol} -> {palette_name} // {comment}")
    out.append("    else -> throw IllegalArgumentException(\"不支持的协议号：$protocol\")")
    out.append("}")
    out.append("")
    return "\n".join(out)


def main() -> int:
    default_root = Path(__file__).resolve().parent.parent.parent
    parser = argparse.ArgumentParser(description="生成 Kotlin 包 ID 表")
    parser.add_argument("--mcc-root", type=Path, default=default_root, help="MCC 仓库根目录")
    parser.add_argument("--check", action="store_true", help="只校验上游文件可解析，不写文件")
    args = parser.parse_args()

    content = generate(args.mcc_root)
    if args.check:
        print(f"上游解析正常，生成内容 {len(content.splitlines())} 行（未写入）")
        return 0

    target = Path(__file__).resolve().parent.parent / "core/src/main/kotlin/mccandroid/core/protocol/PacketIds.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    print(f"已写入 {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())