[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/balcsida/GhydraMCP)](https://github.com/balcsida/GhydraMCP/releases)
[![API Version](https://img.shields.io/badge/API-v3000-orange)](https://github.com/balcsida/GhydraMCP/blob/main/GHIDRA_HTTP_API.md)

# GhydraMCP

GhydraMCP is a bridge between [Ghidra](https://ghidra-sre.org/) and AI assistants that enables AI-assisted reverse engineering through the [Model Context Protocol (MCP)](https://github.com/modelcontextprotocol/mcp).

![GhydraMCP logo](https://github.com/user-attachments/assets/86b9b2de-767c-4ed5-b082-510b8109f00f)

## Overview

GhydraMCP integrates four components:

1. **Ghidra plugin (Ghydra)**: exposes Ghidra's reverse engineering capabilities through a HATEOAS REST API, served by an embedded Javalin server. Builds and runs on Ghidra 11.x and 12.x.
2. **CLI tool (`ghydra`)**: a standalone command-line client with human-readable tables and a `--json` mode for scripting and AI tool use
3. **MCP bridge**: a Python script that translates MCP requests into API calls for Claude Desktop, Claude Code, Cline, etc.
4. **Multi-instance and multi-file architecture**: connect several Ghidra instances, and work on several binaries open in the same instance

This lets AI assistants:
- Decompile and analyze binary code with customizable output formats
- Map program structures, function relationships, and complex data types
- Run cross-reference, call graph, data flow, byte pattern, and scalar (constant) searches
- Make precise modifications to the analysis (rename, annotate, create/delete/modify data)
- Batch operations (rename functions, set comments, define data) in single transactions
- Read/write memory directly
- Manage bookmarks, labels, and data types
- Navigate resources through discoverable HATEOAS links
- Work with multiple open programs in the same Ghidra instance

GhydraMCP is based on [GhidraMCP by Laurie Wired](https://github.com/LaurieWired/GhidraMCP/), with the HATEOAS API, the Javalin plugin, the CLI tool, and the MCP bridge developed by [Starsong Consulting](https://github.com/starsong-consulting/GhydraMCP). This fork tracks upstream and adds multi-file support, batch operations, bookmarks, async decompilation, and a few other features listed below.

> **Note:** Upstream considers the MCP bridge deprecated in favour of the `ghydra` CLI. This fork still maintains the bridge and adds tools to it.

## Fork Additions

On top of upstream 3.0.0-rc.1:

- **Multi-file support**: open, close, and switch between programs in the same Ghidra instance. Add `?program=<name>` to any endpoint to target a specific open program.
- **Batch operations**: rename many functions, set many comments, or define many data items in one atomic transaction, with a per-item status.
- **Bookmark management**: add, list, and delete Ghidra bookmarks.
- **Async decompilation**: start long-running decompilations in the background and poll for the result.
- **Data helpers**: clear a byte range, create labels at arbitrary addresses, inspect data at an address, apply data types (e.g. stamp a struct), and search memory for byte patterns from the bridge.
- **Decompiler constant inlining**: the decompiler is pinned to respect read-only memory (Ghidra's default), so constants from read-only blocks are shown inline.
- **Loopback by default**: the HTTP server binds to 127.0.0.1 only; see [Network access](#network-access) to expose it.
- **No bundled JARs**: builds read the Ghidra JARs and version from `GHIDRA_HOME`.

See [CHANGELOG.md](CHANGELOG.md) for details, including upstream's 3.0 changes (Javalin server, fully-qualified names, scalar search, scripts, program save).

# Features

## Program Analysis

- **Decompilation**: convert functions to readable C with configurable styles, syntax trees, constant inlining, and line filtering; long-running decompiles can run asynchronously
- **Static analysis**: cross-references, call graphs, data flow, type propagation
- **Search**: byte patterns across memory, scalar (constant) values in instructions, functions/symbols/data by name, regex, or address
- **Memory operations**: read/write memory as hex or base64
- **Symbols**: imports, exports, symbols, namespace hierarchy

## Interactive Reverse Engineering

- **Annotation**: rename functions/variables/data, add comments (EOL, plate, pre/post), set function signatures
- **Fully-qualified names**: names such as `FOM::SharedMemory::ReadUInt` are used for lookup and output; renaming to `A::B::name` moves the symbol into that namespace (see [GHIDRA_HTTP_API.md](GHIDRA_HTTP_API.md))
- **Bookmarks**: add, list, and delete bookmarks to track analysis progress
- **Scripts**: run Ghidra scripts through the API (disabled unless the server is started with `-Dghydra.dev.allowScripts=true` or `GHYDRA_ALLOW_SCRIPTS=1`)

## Data Manipulation

- **Data items**: create, delete, rename, retype, clear/undefine bytes, create labels at arbitrary addresses
- **Batch operations**: rename functions, set comments, and define data in bulk
- **Struct/enum/union creation**: create data types with inline fields or values in a single call
- **Apply data types**: stamp structs and other types at memory addresses

## Multiple Instances and Programs

- **Multi-instance**: run several Ghidra instances on ports 8192-8447 (256-port range) with auto-discovery
- **Multi-file**: open, close, and switch between programs in the same Ghidra instance
- **Program targeting**: add `?program=name` to any endpoint to operate on a specific open program without switching
- **Projects**: list project files, open files in CodeBrowser, navigate the folder hierarchy, save programs

# Installation

## Prerequisites
- [Ghidra](https://ghidra-sre.org) 11.x or 12.x
- Python 3.11+ (for the MCP bridge or CLI)
- `GHIDRA_HOME` pointing at your Ghidra installation (only for building from source)

## Ghidra Plugin

Download the latest [release](https://github.com/balcsida/GhydraMCP/releases) from this repository, then install the plugin:

1. Run Ghidra
2. Select `File` -> `Install Extensions`
3. Click the `+` button
4. Select the `Ghydra-*-ghidra<version>.zip` that matches your Ghidra version (e.g. `...-ghidra12.1.2.zip` for Ghidra 12.1.2)
5. Restart Ghidra
6. Make sure the Ghydra plugin is enabled in `File` -> `Configure` -> `Developer`

> **Note:** By default, the first CodeBrowser opened gets port 8192, the second gets 8193, and so on. Check the Ghidra Console (computer icon in bottom right) for log entries like:
> ```
> [GhydraMCP] Plugin loaded on port 8192
> [GhydraMCP] HTTP server started on port 8192
> ```
>
> The bridge and CLI auto-discover running instances, so registering each one by hand is usually unnecessary.

Video installation guide:

https://github.com/user-attachments/assets/75f0c176-6da1-48dc-ad96-c182eb4648c3

### Network access

The plugin's API has no authentication, so it listens on `127.0.0.1` only. To reach it from another host (for example the bridge running in WSL with Ghidra on Windows), start Ghidra with `-Dghidra.mcp.bind.host=0.0.0.0` or set `GHYDRA_BIND_HOST=0.0.0.0`, and restrict access with a firewall.

## CLI Tool

GhydraMCP includes `ghydra`, a command-line tool for talking to Ghidra from the terminal. It works standalone, no MCP client needed.

```bash
# Install
pip install -e .

# List running Ghidra instances
ghydra instances list

# List open programs in an instance
ghydra programs list-open

# Open another binary from the project in the same instance
ghydra programs open /path/in/project/binary

# Decompile a function
ghydra functions decompile --name main

# Search for a constant
ghydra scalars search 0xdeadbeef

# List strings matching a pattern
ghydra data list-strings --filter "password"

# JSON output for scripting
ghydra --json functions list | jq '.result[].name'
```

All commands support `--host`, `--port`, `--json`, and `--no-color` flags. See [GHYDRA_CLI.md](GHYDRA_CLI.md) for the full reference.

## MCP Clients

GhydraMCP works with any MCP-compatible client using **stdio transport**. Tested with:

- **Claude Desktop** - Anthropic's desktop application
- **Claude Code** - Anthropic's CLI tool and VS Code extension
- **Cline** - VS Code extension for AI-assisted coding

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

> **Note:** You can also use `python` instead of `uv run`, but then install the requirements first: `pip install mcp requests pydantic`.

The bridge's HTTP timeout defaults to 900s (`GHIDRA_TIMEOUT`) and its decompilation timeout to 1200s (`GHIDRA_DECOMP_TIMEOUT`).

**Configuration file locations:**
- **Claude Desktop (macOS)**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Claude Desktop (Windows)**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Cline**: Click "MCP Servers" -> "Configure" -> "Configure MCP Servers" in the Cline panel

## Available MCP Tools

Tools are organized into namespaces:

| Namespace | Tools | Fork additions |
|---|---|---|
| `instances_*` | `list`, `discover`, `register`, `unregister`, `use`, `current` | |
| `programs_*` | `list`, `get`, `delete`, `save`, `list_open`, `open`, `close`, `switch` | `list_open`, `open`, `close`, `switch` |
| `projects_*` / `project_*` | `projects_list`, `projects_get`, `project_info`, `project_list_files`, `project_open_file` | |
| `functions_*` | `list`, `get`, `get_containing`, `get_next`, `get_prev`, `decompile`, `decompile_async`, `disassemble`, `create`, `delete`, `rename`, `set_signature`, `get_variables`, `update_variable`, `set_comment` | `decompile_async` |
| `tasks_*` | `get_status`, `get_result` | all |
| `data_*` | `list`, `list_strings`, `create`, `rename`, `delete`, `set_type`, `clear`, `create_label`, `at_address` | `clear`, `create_label`, `at_address` |
| `batch_*` | `rename_functions`, `set_comments`, `define_data` | all |
| `memory_*` | `read`, `write`, `disassemble`, `search_bytes` | `search_bytes` |
| `scalars_*` | `search` | |
| `bookmarks_*` | `list`, `add`, `delete` | all |
| `datatypes_*` | `list`, `search`, `create_struct`, `create_enum`, `create_union`, `apply` | `apply` |
| `structs_*` | `list`, `get`, `create`, `add_field`, `update_field`, `delete` | |
| `xrefs_*` | `list` | |
| `analysis_*` | `run`, `status`, `get_callgraph`, `get_dataflow` | |
| `symbols_*` | `list`, `imports`, `exports` | |
| `scripts_*` | `list`, `run` | |
| Other | `classes_*`, `segments_*`, `namespaces_*`, `variables_*`, `comments_*`, `ui_*` | |

> **Multi-file tip**: the tools with a `program` parameter (`functions_list`, `functions_get`, `functions_decompile`, `functions_decompile_async`, `scalars_search`, and the `bookmarks_*`, `batch_*`, `memory_search_bytes` and `data_*` helpers added by this fork) can target a specific open program by name, e.g. `functions_list(program="malware.exe")`. Other tools use the current program; switch it with `programs_switch`.

# Building from Source

## Prerequisites

Set the `GHIDRA_HOME` environment variable to your Ghidra installation (11.x or 12.x):

```bash
export GHIDRA_HOME=/path/to/ghidra_12.1.2_PUBLIC
```

The build compiles against the JARs in `GHIDRA_HOME` and stamps the extension with the version from `$GHIDRA_HOME/Ghidra/application.properties`, so install the result into that same Ghidra version.

## Build

```bash
# Build everything (plugin + complete package)
mvn clean package

# Build plugin only
mvn clean package -P plugin-only
```

This creates:
- `target/Ghydra-[version].zip` - the Ghidra plugin
- `target/Ghydra-Complete-[version].zip` - complete package with the plugin and bridge script

# Testing

See [TESTING.md](TESTING.md) for details on running the test suites.

# License

Apache License 2.0 - see [LICENSE](LICENSE) for details.

# Credits

- [GhidraMCP by Laurie Wired](https://github.com/LaurieWired/GhidraMCP/): the original Ghidra MCP plugin
- [Starsong Consulting](https://github.com/starsong-consulting/GhydraMCP): the HATEOAS API, the Javalin plugin, the CLI tool (`ghydra`), the MCP bridge, multi-instance support, struct/data type management, and the overall GhydraMCP platform
- Community contributors to the upstream GhydraMCP PRs that inspired features in this fork
