package be.uantwerpen.fti.gui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class GuiController {

    private final GuiService guiService;

    public GuiController(GuiService guiService) {
        this.guiService = guiService;
    }

    @GetMapping({"/", "/dashboard"})
    public String dashboard(
            @RequestParam(required = false) Integer selectedId,
            Model model
    ) {
        model.addAttribute("view", guiService.buildDashboard(selectedId));
        return "dashboard";
    }

    @PostMapping("/nodes/add")
    public String addNode(@RequestParam String nodeName, RedirectAttributes redirectAttributes) {
        try {
            guiService.addNode(nodeName);
            redirectAttributes.addFlashAttribute("successMessage", "Node started: " + nodeName);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not start node: " + e.getMessage());
        }

        return "redirect:/dashboard#nodes";
    }

    @PostMapping("/nodes/remove")
    public String removeNode(@RequestParam String nodeName, RedirectAttributes redirectAttributes) {
        try {
            guiService.shutdownNode(nodeName);
            redirectAttributes.addFlashAttribute("successMessage", "Graceful shutdown sent to node: " + nodeName);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not shutdown node: " + e.getMessage());
        }

        return "redirect:/dashboard#nodes";
    }

    @PostMapping({"/nodes/kill", "/nodes/fail"})
    public String killNode(@RequestParam String nodeName, RedirectAttributes redirectAttributes) {
        try {
            guiService.killNode(nodeName);
            redirectAttributes.addFlashAttribute("successMessage", "Failure simulated for node: " + nodeName);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not simulate failure: " + e.getMessage());
        }

        return "redirect:/dashboard#nodes";
    }

    @PostMapping("/nameserver/start")
    public String startNameserver(RedirectAttributes redirectAttributes) {
        try {
            guiService.startNameserver();
            redirectAttributes.addFlashAttribute("successMessage", "Nameserver started.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not start nameserver: " + e.getMessage());
        }

        return "redirect:/dashboard";
    }

    @PostMapping("/nameserver/stop")
    public String stopNameserver(RedirectAttributes redirectAttributes) {
        try {
            guiService.stopNameserver();
            redirectAttributes.addFlashAttribute("successMessage", "Nameserver stopped.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not stop nameserver: " + e.getMessage());
        }

        return "redirect:/dashboard";
    }

    @PostMapping("/files/upload")
    public String uploadFile(
            @RequestParam String nodeName,
            @RequestParam(required = false) Integer selectedId,
            @RequestParam("file") MultipartFile file,
            RedirectAttributes redirectAttributes
    ) {
        try {
            guiService.uploadFileToNode(nodeName, file);
            redirectAttributes.addFlashAttribute("successMessage", "Uploaded file to node " + nodeName + ": " + file.getOriginalFilename());
            return redirectToSelected(selectedId, "nodes");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not upload file: " + e.getMessage());
            return redirectToSelected(selectedId, "nodes");
        }
    }

    @PostMapping("/files/delete")
    public String deleteFile(
            @RequestParam String nodeName,
            @RequestParam String fileName,
            @RequestParam String location,
            @RequestParam(required = false) Integer selectedId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            guiService.deleteFileOnNode(nodeName, fileName, location);
            redirectAttributes.addFlashAttribute("successMessage", "Deleted " + fileName + " from " + location + " files on " + nodeName + ".");
            return redirectToSelected(selectedId, "nodes");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not delete file: " + e.getMessage());
            return redirectToSelected(selectedId, "nodes");
        }
    }

    private String redirectToSelected(Integer selectedId, String anchor) {
        if (selectedId == null) {
            return "redirect:/dashboard#" + anchor;
        }

        return "redirect:/dashboard?selectedId=" + selectedId + "#" + anchor;
    }
}
