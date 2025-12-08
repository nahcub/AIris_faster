#ifndef PROMPT_GENERATE_H
#define PROMPT_GENERATE_H

#include <string>

/**
 * Creates a RAG-formatted prompt for art docent role
 * @param question User's question about the artwork
 * @return Formatted prompt string with artwork info and question
 */
std::string makeDocentPrompt(const std::string& question);

/**
 * Builds system prompt with artwork info (for prompt caching)
 * @return System prompt string in Qwen chat template format
 */
std::string buildSystemPrompt();

/**
 * Builds user prompt with question (for prompt caching)
 * @param question User's question about the artwork
 * @return User prompt string in Qwen chat template format
 */
std::string buildUserPrompt(const std::string& question);

/**
 * Sets artwork information from Kotlin
 * @param title Artwork title
 * @param author Artist name
 * @param type Artwork type
 * @param technique Artwork technique
 * @param school Artwork school/culture
 * @param date Creation date
 * @param description Artwork description
 */
void setArtworkInfo(const std::string& title, const std::string& author, 
                    const std::string& type, const std::string& technique,
                    const std::string& school, const std::string& date,
                    const std::string& description);

#endif // PROMPT_GENERATE_H

