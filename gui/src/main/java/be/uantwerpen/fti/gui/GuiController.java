package be.uantwerpen.fti.gui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

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
        /*
         * Text editing was removed from the GUI.
         * GuiService still accepts the old optional edit parameters internally,
         * so we pass null values here and keep only upload/delete functionality.
         */
        model.addAttribute("view", guiService.buildDashboard(selectedId, null, null, null));
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
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "uploadFile", required = false) MultipartFile uploadFile,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            UploadedGuiFile selectedFile = extractUploadedFile(file, uploadFile, request);
            guiService.uploadFileToNode(nodeName, selectedFile.filename(), selectedFile.bytes());
            redirectAttributes.addFlashAttribute("successMessage", "Uploaded file to node " + nodeName + ": " + selectedFile.filename());
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

    private UploadedGuiFile extractUploadedFile(
            MultipartFile file,
            MultipartFile uploadFile,
            HttpServletRequest request
    ) throws Exception {
        MultipartFile chosen = firstSelectedMultipartFile(file, uploadFile);

        if (chosen != null) {
            /*
             * Important: do NOT use MultipartFile.isEmpty() here.
             * A valid zero-byte text file is also "empty" according to Spring.
             * We only check whether the browser sent a filename.
             */
            return new UploadedGuiFile(chosen.getOriginalFilename(), chosen.getBytes());
        }

        /*
         * Fallback: inspect all multipart parts. This makes upload robust even if
         * the HTML input name is changed from "file" to something else later.
         */
        try {
            for (Part part : request.getParts()) {
                String submittedName = part.getSubmittedFileName();

                if (submittedName != null && !submittedName.trim().isEmpty()) {
                    try (InputStream inputStream = part.getInputStream()) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        inputStream.transferTo(buffer);
                        return new UploadedGuiFile(submittedName, buffer.toByteArray());
                    }
                }
            }
        } catch (Exception ignored) {
            // If the request is not multipart, the normal error below is clearer.
        }

        throw new IllegalArgumentException(
                "Please choose a file to upload. If you already selected one, make sure the upload form uses multipart/form-data."
        );
    }

    private MultipartFile firstSelectedMultipartFile(MultipartFile... files) {
        if (files == null) {
            return null;
        }

        for (MultipartFile multipartFile : files) {
            if (multipartFile == null) {
                continue;
            }

            String originalFilename = multipartFile.getOriginalFilename();
            if (originalFilename != null && !originalFilename.trim().isEmpty()) {
                return multipartFile;
            }
        }

        return null;
    }

    private String redirectToSelected(Integer selectedId, String anchor) {
        if (selectedId == null) {
            return "redirect:/dashboard#" + anchor;
        }

        return "redirect:/dashboard?selectedId=" + selectedId + "#" + anchor;
    }

    private record UploadedGuiFile(String filename, byte[] bytes) {
    }
}
