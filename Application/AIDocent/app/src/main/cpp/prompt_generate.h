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

#endif // PROMPT_GENERATE_H

