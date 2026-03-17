"""Program management commands for multi-file support."""

import click

from ..client.exceptions import GhidraError
from ..utils import rich_echo


@click.group('programs')
def programs():
    """Manage open programs (multi-file support).

    Commands for opening, closing, switching, and listing programs within
    a single Ghidra instance. This allows analyzing multiple binaries
    simultaneously.
    """
    pass


@programs.command('list-open')
@click.pass_context
def list_open(ctx):
    """List all currently open programs in the Ghidra instance.

    Shows which binaries are open and which is the active/current program.
    Use the program name with --program on other commands to target it.

    \\b
    Example:
        ghydra programs list-open
    """
    client = ctx.obj['client']
    formatter = ctx.obj['formatter']

    try:
        result = client.get("programs/open-programs")
        output = formatter.format_json(result)
        click.echo(output)
    except GhidraError as e:
        rich_echo(formatter.format_error(e), err=True)
        ctx.exit(1)


@programs.command('open')
@click.argument('path')
@click.pass_context
def open_program(ctx, path):
    """Open a project file as a program.

    Opens a binary from the Ghidra project without switching away from the current program.
    Use 'ghydra project list-files' to see available files.

    \\b
    Example:
        ghydra programs open /malware.exe
    """
    client = ctx.obj['client']
    formatter = ctx.obj['formatter']

    try:
        result = client.post("programs/open", json_data={"path": path})
        output = formatter.format_json(result)
        click.echo(output)
    except GhidraError as e:
        rich_echo(formatter.format_error(e), err=True)
        ctx.exit(1)


@programs.command('close')
@click.argument('name')
@click.pass_context
def close_program(ctx, name):
    """Close an open program.

    \\b
    Example:
        ghydra programs close malware.exe
    """
    client = ctx.obj['client']
    formatter = ctx.obj['formatter']

    try:
        result = client.post("programs/close", json_data={"name": name})
        output = formatter.format_json(result)
        click.echo(output)
    except GhidraError as e:
        rich_echo(formatter.format_error(e), err=True)
        ctx.exit(1)


@programs.command('switch')
@click.argument('name')
@click.pass_context
def switch_program(ctx, name):
    """Switch the active/current program.

    Changes which program is the default for all operations.
    Alternatively, pass --program=name to any command.

    \\b
    Example:
        ghydra programs switch malware.exe
    """
    client = ctx.obj['client']
    formatter = ctx.obj['formatter']

    try:
        result = client.post("programs/switch", json_data={"name": name})
        output = formatter.format_json(result)
        click.echo(output)
    except GhidraError as e:
        rich_echo(formatter.format_error(e), err=True)
        ctx.exit(1)
