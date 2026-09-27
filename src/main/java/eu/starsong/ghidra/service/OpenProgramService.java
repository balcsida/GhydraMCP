package eu.starsong.ghidra.service;

import eu.starsong.ghidra.server.GhidraContext;
import eu.starsong.ghidra.server.GhydraServer.BadRequestException;
import eu.starsong.ghidra.server.GhydraServer.NotFoundException;
import eu.starsong.ghidra.util.GhidraSwing;
import ghidra.app.services.ProgramManager;
import ghidra.framework.model.DomainFile;
import ghidra.framework.model.Project;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.listing.Program;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-file support: manage the set of programs open in one tool (CodeBrowser).
 *
 * <p>All ProgramManager calls run on the Swing thread; opening, closing and switching
 * programs are UI operations in Ghidra.
 */
public class OpenProgramService {

    /**
     * List every program open in the tool, flagging the current one.
     */
    public List<Map<String, Object>> listOpen(PluginTool tool) {
        ProgramManager pm = requireProgramManager(tool);
        return GhidraSwing.runRead(() -> {
            Program current = pm.getCurrentProgram();
            List<Map<String, Object>> programs = new ArrayList<>();
            for (Program p : pm.getAllOpenPrograms()) {
                Map<String, Object> info = describe(p);
                info.put("isCurrent", p.equals(current));
                programs.add(info);
            }
            return programs;
        });
    }

    /**
     * Open a project file in this tool without making it the current program.
     */
    public Map<String, Object> open(PluginTool tool, String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        Project project = tool != null ? tool.getProject() : null;
        if (project == null) {
            throw new ProjectService.NoProjectException("No project is currently open");
        }
        ProgramManager pm = requireProgramManager(tool);

        return GhidraSwing.runRead(() -> {
            DomainFile file = project.getProjectData().getFile(path);
            if (file == null) {
                throw new NotFoundException("File not found in project: " + path, "FILE_NOT_FOUND");
            }

            for (Program p : pm.getAllOpenPrograms()) {
                if (file.equals(p.getDomainFile())) {
                    Map<String, Object> info = describe(p);
                    info.put("alreadyOpen", true);
                    return info;
                }
            }

            // OPEN_VISIBLE keeps the program open without switching the current program.
            Program opened = pm.openProgram(file, DomainFile.DEFAULT_VERSION, ProgramManager.OPEN_VISIBLE);
            if (opened == null) {
                throw new IllegalStateException("Failed to open file: " + path);
            }
            Map<String, Object> info = describe(opened);
            info.put("opened", true);
            return info;
        });
    }

    /**
     * Close an open program. Refuses when it has unsaved changes unless discard is set,
     * so a close can never silently throw away analysis.
     */
    public Map<String, Object> close(PluginTool tool, String name, boolean discard) {
        ProgramManager pm = requireProgramManager(tool);
        return GhidraSwing.runRead(() -> {
            Program target = requireOpen(pm, name);
            if (target.isChanged() && !discard) {
                throw new BadRequestException("Program '" + target.getName()
                    + "' has unsaved changes; save it first or pass discard=true", "UNSAVED_CHANGES");
            }
            String programName = target.getName();
            boolean closed = pm.closeProgram(target, true);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", programName);
            result.put("closed", closed);
            return result;
        });
    }

    /**
     * Make an open program the tool's current program.
     */
    public Map<String, Object> switchTo(PluginTool tool, String name) {
        ProgramManager pm = requireProgramManager(tool);
        return GhidraSwing.runRead(() -> {
            Program target = requireOpen(pm, name);
            pm.setCurrentProgram(target);
            Map<String, Object> result = describe(target);
            result.put("switched", true);
            return result;
        });
    }

    private static Program requireOpen(ProgramManager pm, String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        Program target = GhidraContext.findOpenProgram(pm, name);
        if (target == null) {
            throw new NotFoundException("Program not open: " + name, "PROGRAM_NOT_FOUND");
        }
        return target;
    }

    private static ProgramManager requireProgramManager(PluginTool tool) {
        ProgramManager pm = tool != null ? tool.getService(ProgramManager.class) : null;
        if (pm == null) {
            throw new IllegalStateException("ProgramManager service not available");
        }
        return pm;
    }

    private static Map<String, Object> describe(Program p) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", p.getName());
        info.put("path", p.getDomainFile() != null ? p.getDomainFile().getPathname() : null);
        info.put("language", p.getLanguage().getLanguageID().getIdAsString());
        info.put("processor", p.getLanguage().getProcessor().toString());
        info.put("addressSize", p.getAddressFactory().getDefaultAddressSpace().getSize());
        info.put("imageBase", p.getImageBase().toString());
        info.put("memorySize", p.getMemory().getSize());
        info.put("executablePath", p.getExecutablePath());
        info.put("changed", p.isChanged());
        return info;
    }
}
