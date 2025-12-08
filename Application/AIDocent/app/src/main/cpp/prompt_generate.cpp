#include "prompt_generate.h"
#include <string>
#include <sstream>

// Global artwork information storage
static std::string artwork_title = "";
static std::string artwork_author = "";
static std::string artwork_type = "";
static std::string artwork_technique = "";
static std::string artwork_school = "";
static std::string artwork_date = "";
static std::string artwork_description = "";

// Helper function to format artwork information
static std::string formatArtworkInfo() {
    std::ostringstream info;
    info << "[ARTWORK INFO]\n\n";
    
    if (!artwork_title.empty()) {
        info << "Title: " << artwork_title << "\n";
    }
    if (!artwork_date.empty()) {
        info << "Object Date: " << artwork_date << "\n";
    }
    if (!artwork_author.empty()) {
        info << "Artist Display Name: " << artwork_author << "\n";
    }
    if (!artwork_technique.empty()) {
        info << "Medium: " << artwork_technique << "\n";
    }
    if (!artwork_type.empty()) {
        info << "Type: " << artwork_type << "\n";
    }
    if (!artwork_description.empty()) {
        info << "Description: " << artwork_description << "\n";
    }
    
    info << "\n";
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

void setArtworkInfo(const std::string& title, const std::string& author, 
                    const std::string& type, const std::string& technique,
                    const std::string& school, const std::string& date,
                    const std::string& description) {
    artwork_title = title;
    artwork_author = author;
    artwork_type = type;
    artwork_technique = technique;
    artwork_school = school;
    artwork_date = date;
    artwork_description = description;
}