package be.uantwerpen.fti.gui;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
        model.addAttribute("view", guiService.buildDashboardView(selectedId));
        return "dashboard";
    }

    @PostMapping("/nodes/add")
    public String addNode(
            @RequestParam String nodeName,
            RedirectAttributes redirectAttributes
    ) {
        try {
            guiService.addNode(nodeName);
            redirectAttributes.addFlashAttribute("successMessage", "Node started: " + nodeName);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not start node: " + e.getMessage());
        }

        return "redirect:/dashboard";
    }

    @PostMapping("/nodes/remove")
    public String removeNode(
            @RequestParam String nodeName,
            RedirectAttributes redirectAttributes
    ) {
        try {
            guiService.removeNode(nodeName);
            redirectAttributes.addFlashAttribute("successMessage", "Node stopped: " + nodeName);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not stop node: " + e.getMessage());
        }

        return "redirect:/dashboard";
    }

    @PostMapping("/nodes/kill")
    public String killNode(
            @RequestParam String nodeName,
            RedirectAttributes redirectAttributes
    ) {
        try {
            guiService.killNode(nodeName);
            redirectAttributes.addFlashAttribute("successMessage", "Node killed as failure simulation: " + nodeName);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not kill node: " + e.getMessage());
        }

        return "redirect:/dashboard";
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

    @PostMapping("/files/create")
    public String createFile(
            @RequestParam String nodeName,
            @RequestParam String fileName,
            @RequestParam(defaultValue = "") String content,
            RedirectAttributes redirectAttributes
    ) {
        try {
            guiService.createFileOnNode(nodeName, fileName, content);
            redirectAttributes.addFlashAttribute("successMessage", "File created on node " + nodeName + ": " + fileName);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not create file: " + e.getMessage());
        }

        return "redirect:/dashboard";
    }
}
