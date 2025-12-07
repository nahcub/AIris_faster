#include "prompt_generate.h"
#include <string>
#include <sstream>

// Helper function to format artwork information
static std::string formatArtworkInfo() {
    // Hardcoded artwork information
    const std::string title = "I and the Village";
    const std::string objectDate = "1911";
    const std::string culture = "Russian-Jewish";
    const std::string artistDisplayName = "Marc Chagall";
    const std::string medium = "Oil on canvas";
    const std::string country = "Russia";
    const std::string period = "Modernism";
    
    std::ostringstream info;
    info << "[ARTWORK INFO]\n\n";
    info << "Title: " << title << "\n";
    info << "Object Date: " << objectDate << "\n";
    info << "Culture: " << culture << "\n";
    info << "Artist Display Name: " << artistDisplayName << "\n";
    info << "Medium: " << medium << "\n";
    info << "Country: " << country << "\n";
    info << "Period: " << period << "\n\n";
    
    return info.str();
}

std::string buildSystemPrompt() {
    // Build system prompt using Qwen chat template format
    std::ostringstream prompt;
    
    prompt << "<|im_start|>system\n";
    prompt << formatArtworkInfo();
    prompt << "<|im_end|>\n";
    
    return prompt.str();
}

std::string buildUserPrompt(const std::string& question) {
    // Build user prompt using Qwen chat template format
    std::ostringstream prompt;
    
    prompt << "<|im_start|>user\n";
    prompt << "[QUESTION]\n\n";
    prompt << question;
    //prompt << " /no_think";
    prompt << "\n<|im_end|>\n";  
    prompt << "<|im_start|>assistant";
    
    return prompt.str();
}