[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/balcsida/GhydraMCP)](https://github.com/balcsida/GhydraMCP/releases)
[![API Version](https://img.shields.io/badge/API-v2030-orange)](https://github.com/balcsida/GhydraMCP/blob/main/GHIDRA_HTTP_API.md)

# GhydraMCP v2.3.0

GhydraMCP is a powerful bridge between [Ghidra](https://ghidra-sre.org/) and AI assistants that enables comprehensive AI-assisted reverse engineering through the [Model Context Protocol (MCP)](https://github.com/modelcontextprotocol/mcp).

![GhydraMCP logo](https://github.com/user-attachments/assets/86b9b2de-767c-4ed5-b082-510b8109f00f)

## Overview

GhydraMCP v2.3.0 integrates four key components:

1. **Modular Ghidra Plugin**: Exposes Ghidra's reverse engineering capabilities through a HATEOAS-driven REST API
2. **CLI Tool (`ghydra`)**: A standalone command-line interface for direct interaction with Ghidra -- human-readable tables, `--json` mode for AI tool use and scripting
3. **MCP Bridge**: A Python script that translates MCP requests into API calls for use with Claude Desktop, Claude Code, Cline, etc.
4. **Multi-instance & Multi-file Architecture**: Connect multiple Ghidra instances and analyze multiple binaries open within the same instance simultaneously

This architecture enables AI assistants like Claude to seamlessly:
- Decompile and analyze binary code with customizable output formats
- Map program structures, function relationships, and complex data types
- Perform advanced binary analysis (cross-references, call graphs, data flow, byte pattern search)
- Make precise modifications to the analysis (rename, annotate, create/delete/modify data)
- Batch operations (rename functions, set comments, define data) in single transactions
- Read/write memory directly and search for byte patterns
- Manage bookmarks, labels, and data types
- Navigate resources through discoverable HATEOAS links
- Work with multiple open programs in the same Ghidra instance

GhydraMCP is based on [GhidraMCP by Laurie Wired](https://github.com/LaurieWired/GhidraMCP/), with the modular HATEOAS API architecture, CLI tool, and MCP bridge originally developed by [Starsong Consulting](https://github.com/starsong-consulting/GhydraMCP). This fork adds multi-file support, batch operations, async decompilation, and features from upstream community PRs.

## What's New in v2.3.0

- **Multi-file support**: Open, close, and switch between multiple programs in the same Ghidra instance. Pass `?program=name` to any endpoint to target a specific open program.
- **Batch operations**: Rename multiple functions, set comments at many addresses, and define data items in bulk -- all in single atomic transactions.
- **Byte pattern search**: Search all program memory for byte patterns (`GET /memory/search?bytes=4D5A`).
- **Bookmark management**: Add, list, and delete Ghidra bookmarks via API.
- **Async decompilation**: Start long-running decompilations in the background and poll for results.
- **Data enhancements**: Clear/undefine data, create labels at arbitrary addresses, get detailed data info at specific addresses, apply structs at memory locations.
- **Inline data type creation**: Create structs with fields, enums with values, and unions with members in a single call.
- **Decompiler constant inlining**: Constant values from read-only memory are now shown inline in decompiled output.
- **Security**: HTTP server now binds to 127.0.0.1 only (not all network interfaces).
- **Removed bundled JARs**: Set `GHIDRA_HOME` environment variable instead. Reduces repo size by ~33MB.

# Features

## Advanced Program Analysis

- **Enhanced Decompilation**: Convert binary functions to readable C code with configurable styles, syntax trees, constant inlining, and line filtering
- **Async Decompilation**: Start decompilation in the background and poll for results (for large/complex functions)
- **Comprehensive Static Analysis**: Cross-reference analysis, call graph generation, data flow analysis, type propagation
- **Memory Operations**: Direct memory reading/writing with hex and base64 representation, byte pattern search across all memory blocks
- **Symbol Management**: View and analyze imports, exports, symbols, namespace hierarchy

## Interactive Reverse Engineering

- **Code Understanding**: Explore function code, data structures, disassembly with linking to decompiled code
- **Comprehensive Annotation**: Rename functions/variables/data, add comments (EOL, plate, pre/post), create/modify data types, set function signatures
- **Bookmark Management**: Add, list, and delete bookmarks for tracking analysis progress

## Data Manipulation

- **Data Items**: Create, delete, rename, retype data items, clear/undefine bytes, create labels at arbitrary addresses
- **Batch Operations**: Rename multiple functions, set comments at many addresses, define data items in bulk -- all in single transactions
- **Struct/Enum/Union Creation**: Create complex data types with inline field/value definitions in a single call
- **Apply Data Types**: Stamp structs and other data types at memory addresses

## Multi-instance & Multi-file Support

- **Multi-instance**: Run multiple Ghidra instances simultaneously on ports 8192-8447 (256 port range) with auto-discovery
- **Multi-file**: Open, close, and switch between multiple programs within the same Ghidra instance
- **Program Targeting**: Pass `?program=name` to any endpoint (or `program=name` to any MCP tool) to operate on a specific open program without switching
- **Project Management**: List project files, open files in CodeBrowser, navigate folder hierarchy

# Installation

## Prerequisites
- [Ghidra](https://ghidra-sre.org) (11.4.2+ recommended)
- Python 3.11+ (for MCP bridge or CLI)
- `GHIDRA_HOME` environment variable set to your Ghidra installation directory (for building from source)

## Ghidra Plugin

Download the latest [release](https://github.com/balcsida/GhydraMCP/releases) from this repository. Then install the plugin:

1. Run Ghidra
2. Select `File` -> `Install Extensions`
3. Click the `+` button
4. Select the `GhydraMCP-[version].zip` file from the downloaded release
5. Restart Ghidra
6. Make sure the GhydraMCPPlugin is enabled in `File` -> `Configure` -> `Developer`

> **Note:** By default, the first CodeBrowser opened gets port 8192, the second gets 8193, and so on. Check the Ghidra Console (computer icon in bottom right) for log entries like:
> ```
> [GhydraMCP] Plugin loaded on port 8192
> [GhydraMCP] HTTP server started on port 8192
> ```
>
> GhydraMCP includes auto-discovery of running instances, so manually registering each instance is typically not necessary.

Video Installation Guide:

https://github.com/user-attachments/assets/75f0c176-6da1-48dc-ad96-c182eb4648c3

## CLI Tool

GhydraMCP includes `ghydra`, a command-line tool for interacting with Ghidra directly from the terminal. It works standalone -- no MCP client needed.

```bash
# Install
pip install -e .

# List running Ghidra instances
ghydra instances list

# List open programs in an instance
ghydra programs list-open

# Open another binary in the same instance
ghydra programs open /path/to/binary

# Decompile a function
ghydra functions decompile --name main

# Decompile from a specific open program
ghydra functions decompile --name main --program malware.exe

# List strings matching a pattern
ghydra data list-strings --filter "password"

# JSON output for scripting
ghydra --json functions list | jq '.result[].name'
```

All commands support `--host`, `--port`, `--json`, and `--no-color` flags. See [GHYDRA_CLI.md](GHYDRA_CLI.md) for the full reference.

## MCP Clients

GhydraMCP works with any MCP-compatible client using **stdio transport**. Tested with:

- **Claude Desktop** - Anthropic's official desktop application
- **Claude Code** - Anthropic's CLI tool and VS Code extension
- **Cline** - Popular VS Code extension for AI-assisted coding

### Configuration

Add to your MCP client's configuration:

```json
{
  "mcpServers": {
    "ghydra": {
      "command": "uv",
      "args": [
        "run",
        "/ABSOLUTE_PATH_TO/bridge_mcp_hydra.py"
      ],
      "env": {
        "GHIDRA_HYDRA_HOST": "localhost"
      }
    }
  }
}
```

Replace `/ABSOLUTE_PATH_TO/` with the actual path to your `bridge_mcp_hydra.py` file.

> **Note:** You can also use `python` instead of `uv run`, but then install requirements first: `pip install mcp requests`.

**Configuration file locations:**
- **Claude Desktop (macOS)**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Claude Desktop (Windows)**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Cline**: Click "MCP Servers" -> "Configure" -> "Configure MCP Servers" in the Cline panel

## Available MCP Tools

GhydraMCP v2.3.0 organizes tools into logical namespaces:

| Namespace | Tools | New in v2.3.0 |
|---|---|---|
| `instances_*` | `list`, `discover`, `register`, `unregister`, `use`, `current` | |
| `programs_*` | `list_open`, `open`, `close`, `switch` | All new |
| `functions_*` | `list`, `get`, `decompile`, `disassemble`, `create`, `rename`, `set_signature`, `get_variables`, `set_comment`, `decompile_async` | `decompile_async` |
| `tasks_*` | `get_status`, `get_result` | All new |
| `data_*` | `list`, `list_strings`, `create`, `rename`, `delete`, `set_type`, `clear`, `create_label`, `at_address` | `clear`, `create_label`, `at_address` |
| `batch_*` | `rename_functions`, `set_comments`, `define_data` | All new |
| `memory_*` | `read`, `write`, `search_bytes` | `search_bytes` |
| `bookmarks_*` | `list`, `add`, `delete` | All new |
| `datatypes_*` | `list`, `search`, `apply` | `apply` |
| `structs_*` | `list`, `get`, `create`, `add_field`, `update_field`, `delete` | |
| `xrefs_*` | `list` | |
| `analysis_*` | `run`, `status`, `get_callgraph`, `get_dataflow` | |
| `symbols_*` | `list`, `imports`, `exports` | |
| Other | `classes_*`, `segments_*`, `namespaces_*`, `variables_*`, `project_*`, `comments_*`, `ui_*` | |

> **Multi-file tip**: Most tools accept a `program` parameter to target a specific open program by name (e.g. `functions_list(program="malware.exe")`). Without it, the active/current program is used.

# Building from Source

## Prerequisites

Set the `GHIDRA_HOME` environment variable to your Ghidra installation:

```bash
export GHIDRA_HOME=/path/to/ghidra_11.4.2_PUBLIC
```

## Build

```bash
# Build everything (plugin + complete package)
mvn clean package

# Build plugin only
mvn clean package -P plugin-only
```

This creates:
- `target/GhydraMCP-[version].zip` - The Ghidra plugin
- `target/GhydraMCP-Complete-[version].zip` - Complete package with plugin and bridge script

# Testing

See [TESTING.md](TESTING.md) for details on running the test suites.

# License

Apache License 2.0 - see [LICENSE](LICENSE) for details.

# Credits

- [GhidraMCP by Laurie Wired](https://github.com/LaurieWired/GhidraMCP/) — the original Ghidra MCP plugin
- [Starsong Consulting](https://github.com/starsong-consulting/GhydraMCP) — modular HATEOAS API architecture, CLI tool (`ghydra`), MCP bridge rewrite, multi-instance support, struct/data type management, and the overall GhydraMCP platform
- Community contributors to the upstream GhidraMCP PRs that inspired features in this release
