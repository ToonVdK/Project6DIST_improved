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
            @RequestParam(value = "selectedId", required = false) Integer selectedId,
            Model model
    ) {
        model.addAttribute("view", guiService.loadDashboard(selectedId));
        return "dashboard";
    }

    @PostMapping("/nodes/add")
    public String addNode(
            @RequestParam("nodeName") String nodeName,
            RedirectAttributes redirectAttributes
    ) {
        try {
            String output = guiService.addNode(nodeName);
            redirectAttributes.addFlashAttribute("successMessage", "Node started: " + output);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not start node: " + e.getMessage());
        }

        return "redirect:/dashboard";
    }

    @PostMapping("/nodes/remove")
    public String removeNode(
            @RequestParam("nodeName") String nodeName,
            RedirectAttributes redirectAttributes
    ) {
        try {
            String output = guiService.removeNode(nodeName);
            redirectAttributes.addFlashAttribute("successMessage", "Node stopped: " + output);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Could not stop node: " + e.getMessage());
        }

        return "redirect:/dashboard";
    }
}
