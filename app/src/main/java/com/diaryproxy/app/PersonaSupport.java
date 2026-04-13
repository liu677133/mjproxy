package com.diaryproxy.app;

import android.text.TextUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PersonaSupport {

    private PersonaSupport() {
    }

    static PersonaParseResult parseCompatibleCard(String rawJson) {
        if (TextUtils.isEmpty(rawJson)) {
            return PersonaParseResult.error("empty_json");
        }
        try {
            JSONObject root = new JSONObject(rawJson);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return PersonaParseResult.error("missing_data");
            }
            String name = data.optString("name", "").trim();
            String description = data.optString("description", "").trim();
            String personality = data.optString("personality", "").trim();
            String scenario = data.optString("scenario", "").trim();
            String creatorNotes = data.optString("creator_notes", "").trim();
            PersonaCard card = new PersonaCard(rawJson, name, description, personality, scenario, creatorNotes);
            return PersonaParseResult.success(card);
        } catch (Exception error) {
            return PersonaParseResult.error("json_parse_failed:" + error.getMessage());
        }
    }

    static String renderCorePrompt(PersonaCard card) {
        if (card == null) {
            return "";
        }
        String name = TextUtils.isEmpty(card.name) ? "Yuki" : card.name;
        return "你是" + name + "，请根据以下角色设定进行对话：\n\n"
                + safe(card.description) + "\n\n"
                + "性格特征：\n"
                + safe(card.personality) + "\n\n"
                + "场景设定：\n"
                + safe(card.scenario) + "\n\n"
                + "重要提示：\n"
                + safe(card.creatorNotes);
    }

    static PersonaOverlayResult tryOverlay(String systemText, ProxyConfig config) {
        if (TextUtils.isEmpty(systemText)) {
            return PersonaOverlayResult.skip("system_empty");
        }
        if (config == null || !config.personaEnabled) {
            return PersonaOverlayResult.skip("persona_disabled");
        }
        if (looksLikeInteractiveStoryMode(systemText)) {
            return tryOverlayInteractiveStory(systemText, config);
        }
        if (looksLikeExcludedMode(systemText)) {
            return PersonaOverlayResult.skip("excluded_mode");
        }

        PromptSections sections = PromptSections.parse(systemText);
        if (!sections.isValid()) {
            return PersonaOverlayResult.skip("section_parse_failed");
        }

        Map<String, PersonaCard> builtinCards = loadCards(config, true);
        Detection detection = detectTier(sections, builtinCards);
        if (!detection.matched()) {
            return PersonaOverlayResult.skip(detection.reason);
        }

        Map<String, PersonaCard> runtimeCards = loadCards(config, false);
        PersonaCard replacement = runtimeCards.get(detection.tier);
        if (replacement == null) {
            return PersonaOverlayResult.skip("persona_missing:" + detection.tier);
        }
        if (!replacement.hasRequiredFields()) {
            return PersonaOverlayResult.skip("persona_missing_fields:" + detection.tier);
        }

        String updatedScenario = replaceOnce(sections.scenarioBody, detection.currentCard.scenario, replacement.scenario);
        if (updatedScenario == null) {
            return PersonaOverlayResult.skip("scenario_boundary_failed:" + detection.tier);
        }
        String updatedNotes = replaceOnce(sections.creatorBody, detection.currentCard.creatorNotes, replacement.creatorNotes);
        if (updatedNotes == null) {
            return PersonaOverlayResult.skip("creator_notes_boundary_failed:" + detection.tier);
        }

        String rewritten = sections.introPrefix
                + replacement.description
                + sections.personalityMarker
                + replacement.personality
                + sections.scenarioMarker
                + updatedScenario
                + sections.creatorMarker
                + updatedNotes
                + sections.suffix;

        if (TextUtils.equals(systemText, rewritten)) {
            return PersonaOverlayResult.skip("persona_no_change:" + detection.tier);
        }
        return PersonaOverlayResult.success(detection.tier, rewritten);
    }

    private static Map<String, PersonaCard> loadCards(ProxyConfig config, boolean builtin) {
        Map<String, PersonaCard> cards = new LinkedHashMap<>();
        for (String tier : ProxyConfig.PERSONA_TIERS) {
            String raw = builtin ? config.getBuiltinPersonaJson(tier) : config.getPersonaJson(tier);
            PersonaParseResult parsed = parseCompatibleCard(raw);
            if (parsed.card != null) {
                cards.put(tier, parsed.card);
            }
        }
        return cards;
    }

    private static Detection detectTier(PromptSections sections, Map<String, PersonaCard> cards) {
        String currentDescription = normalize(sections.description);
        String currentPersonality = normalize(sections.personalityBody);
        Map<String, String> currentDescriptionFields = parseBracketFields(sections.description);

        Detection best = Detection.none("tier_not_identified");
        for (Map.Entry<String, PersonaCard> entry : cards.entrySet()) {
            PersonaCard card = entry.getValue();
            int score = 0;
            if (TextUtils.equals(currentDescription, normalize(card.description))) {
                score += 6;
            } else {
                score += scoreDescriptionFieldMatch(currentDescriptionFields, parseBracketFields(card.description));
            }
            if (TextUtils.equals(currentPersonality, normalize(card.personality))) {
                score += 4;
            } else {
                score += scoreTokenOverlap(sections.personalityBody, card.personality, 3);
            }
            if (!TextUtils.isEmpty(card.scenario) && normalize(sections.scenarioBody).contains(normalize(card.scenario))) {
                score += 1;
            }
            if (!TextUtils.isEmpty(card.creatorNotes) && normalize(sections.creatorBody).contains(normalize(card.creatorNotes))) {
                score += 1;
            }
            if (score > best.score) {
                best = new Detection(entry.getKey(), card, score, score >= 6 ? "matched" : "weak_match");
            }
        }
        if (best.score >= 6) {
            return best;
        }
        return Detection.none(best.reason);
    }

    private static PersonaOverlayResult tryOverlayInteractiveStory(String systemText, ProxyConfig config) {
        Matcher descriptionMatcher = STORY_DESCRIPTION_PATTERN.matcher(systemText);
        if (!descriptionMatcher.find()) {
            return PersonaOverlayResult.skip("story_description_missing");
        }
        Matcher personalityMatcher = STORY_PERSONALITY_PATTERN.matcher(systemText);
        if (!personalityMatcher.find()) {
            return PersonaOverlayResult.skip("story_personality_missing");
        }

        String currentDescription = safe(descriptionMatcher.group(2));
        String currentPersonality = safe(personalityMatcher.group(2));
        Detection detection = detectInteractiveStoryTier(currentDescription, currentPersonality, loadCards(config, true));
        if (!detection.matched()) {
            return PersonaOverlayResult.skip(detection.reason);
        }

        PersonaCard replacement = loadCards(config, false).get(detection.tier);
        if (replacement == null) {
            return PersonaOverlayResult.skip("persona_missing:" + detection.tier);
        }
        if (!replacement.hasStoryRequiredFields()) {
            return PersonaOverlayResult.skip("persona_missing_story_fields:" + detection.tier);
        }

        String rewritten = systemText.substring(0, descriptionMatcher.start(2))
                + replacement.description
                + systemText.substring(descriptionMatcher.end(2), personalityMatcher.start(2))
                + replacement.personality
                + systemText.substring(personalityMatcher.end(2));
        if (TextUtils.equals(systemText, rewritten)) {
            return PersonaOverlayResult.skip("persona_no_change:" + detection.tier);
        }
        return PersonaOverlayResult.success(detection.tier, rewritten);
    }

    private static Detection detectInteractiveStoryTier(String currentDescription, String currentPersonality, Map<String, PersonaCard> cards) {
        String normalizedDescription = normalize(currentDescription);
        String normalizedPersonality = normalize(currentPersonality);
        Map<String, String> currentDescriptionFields = parseBracketFields(currentDescription);
        Detection best = Detection.none("story_tier_not_identified");
        for (Map.Entry<String, PersonaCard> entry : cards.entrySet()) {
            PersonaCard card = entry.getValue();
            int score = 0;
            if (TextUtils.equals(normalizedDescription, normalize(card.description))) {
                score += 6;
            } else {
                score += scoreDescriptionFieldMatch(currentDescriptionFields, parseBracketFields(card.description));
            }
            if (!TextUtils.isEmpty(card.personality) && TextUtils.equals(normalizedPersonality, normalize(card.personality))) {
                score += 4;
            } else {
                score += scoreTokenOverlap(currentPersonality, card.personality, 3);
            }
            if (score > best.score) {
                best = new Detection(entry.getKey(), card, score, score >= 6 ? "matched" : "weak_story_match");
            }
        }
        if (best.score >= 6) {
            return best;
        }
        return Detection.none(best.reason);
    }

    private static Map<String, String> parseBracketFields(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (TextUtils.isEmpty(text)) {
            return fields;
        }
        Matcher matcher = BRACKET_FIELD_PATTERN.matcher(text);
        while (matcher.find()) {
            String key = normalize(matcher.group(1));
            String value = normalize(matcher.group(2));
            if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(value) && !fields.containsKey(key)) {
                fields.put(key, value);
            }
        }
        return fields;
    }

    private static int scoreDescriptionFieldMatch(Map<String, String> currentFields, Map<String, String> candidateFields) {
        if (currentFields.isEmpty() || candidateFields.isEmpty()) {
            return 0;
        }
        int matched = 0;
        for (Map.Entry<String, String> entry : candidateFields.entrySet()) {
            String currentValue = currentFields.get(entry.getKey());
            if (TextUtils.isEmpty(currentValue)) {
                continue;
            }
            if (TextUtils.equals(currentValue, entry.getValue())) {
                matched++;
            }
        }
        if (matched >= 4) {
            return 5;
        }
        if (matched == 3) {
            return 4;
        }
        if (matched == 2) {
            return 2;
        }
        return 0;
    }

    private static int scoreTokenOverlap(String currentText, String candidateText, int maxScore) {
        List<String> currentTokens = tokenizeMeaningful(normalize(currentText));
        List<String> candidateTokens = tokenizeMeaningful(normalize(candidateText));
        if (currentTokens.isEmpty() || candidateTokens.isEmpty()) {
            return 0;
        }
        LinkedHashSet<String> currentSet = new LinkedHashSet<>(currentTokens);
        int matched = 0;
        for (String token : new LinkedHashSet<>(candidateTokens)) {
            if (currentSet.contains(token)) {
                matched++;
            }
        }
        int base = Math.min(currentSet.size(), new LinkedHashSet<>(candidateTokens).size());
        if (base <= 0) {
            return 0;
        }
        float ratio = matched / (float) base;
        if (ratio >= 0.8f) {
            return maxScore;
        }
        if (ratio >= 0.6f) {
            return Math.max(1, maxScore - 1);
        }
        if (ratio >= 0.45f) {
            return 1;
        }
        return 0;
    }

    private static List<String> tokenizeMeaningful(String normalizedText) {
        List<String> tokens = new ArrayList<>();
        if (TextUtils.isEmpty(normalizedText)) {
            return tokens;
        }
        String[] roughTokens = normalizedText.split("[,，;；、\\[\\]\\(\\):：\\n]+");
        for (String token : roughTokens) {
            String value = token == null ? "" : token.trim();
            if (value.length() >= 2) {
                tokens.add(value);
            }
        }
        return tokens;
    }

    private static boolean looksLikeInteractiveStoryMode(String text) {
        String normalized = normalize(text);
        return normalized.contains("互动式短剧生成器")
                || normalized.contains("小剧场")
                || normalized.contains("story_end")
                || normalized.contains("剧情生成规则")
                || normalized.contains("角色人设");
    }

    private static boolean looksLikeExcludedMode(String text) {
        String normalized = normalize(text);
        return normalized.contains("trpg")
                || normalized.contains("gm")
                || normalized.contains("gamemaster")
                || normalized.contains("故事模式")
                || normalized.contains("剧情模式")
                || normalized.contains("旁白")
                || normalized.contains("主持人")
                || normalized.contains("预制回复")
                || normalized.contains("reply1")
                || normalized.contains("reply2");
    }

    private static String replaceOnce(String source, String target, String replacement) {
        if (TextUtils.isEmpty(source) || TextUtils.isEmpty(target)) {
            return null;
        }
        int index = source.indexOf(target);
        if (index < 0) {
            return null;
        }
        return source.substring(0, index) + safe(replacement) + source.substring(index + target.length());
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("：", ":")
                .replace("（", "(")
                .replace("）", ")")
                .replaceAll("\\s+", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private static final Pattern STORY_DESCRIPTION_PATTERN = Pattern.compile("(?m)^\\s*(描述[：:])([^\\r\\n]+)");
    private static final Pattern STORY_PERSONALITY_PATTERN = Pattern.compile("(?m)^\\s*(性格[：:])([^\\r\\n]+)");

    private static final Pattern BRACKET_FIELD_PATTERN = Pattern.compile("\\[([^:\\]：]+)[:：]([^\\]]+)]");

    static final class PersonaCard {
        final String rawJson;
        final String name;
        final String description;
        final String personality;
        final String scenario;
        final String creatorNotes;

        PersonaCard(String rawJson, String name, String description, String personality, String scenario, String creatorNotes) {
            this.rawJson = rawJson;
            this.name = name;
            this.description = description;
            this.personality = personality;
            this.scenario = scenario;
            this.creatorNotes = creatorNotes;
        }

        boolean hasRequiredFields() {
            return !TextUtils.isEmpty(description)
                    && !TextUtils.isEmpty(personality)
                    && !TextUtils.isEmpty(scenario)
                    && !TextUtils.isEmpty(creatorNotes);
        }

        boolean hasStoryRequiredFields() {
            return !TextUtils.isEmpty(description)
                    && !TextUtils.isEmpty(personality);
        }
    }

    static final class PersonaParseResult {
        final PersonaCard card;
        final String reason;

        private PersonaParseResult(PersonaCard card, String reason) {
            this.card = card;
            this.reason = reason;
        }

        static PersonaParseResult success(PersonaCard card) {
            return new PersonaParseResult(card, "ok");
        }

        static PersonaParseResult error(String reason) {
            return new PersonaParseResult(null, reason);
        }
    }

    static final class PersonaOverlayResult {
        final boolean applied;
        final String tier;
        final String rewrittenSystem;
        final String reason;

        private PersonaOverlayResult(boolean applied, String tier, String rewrittenSystem, String reason) {
            this.applied = applied;
            this.tier = tier;
            this.rewrittenSystem = rewrittenSystem;
            this.reason = reason;
        }

        static PersonaOverlayResult success(String tier, String rewrittenSystem) {
            return new PersonaOverlayResult(true, tier, rewrittenSystem, "ok");
        }

        static PersonaOverlayResult skip(String reason) {
            return new PersonaOverlayResult(false, "", "", reason);
        }
    }

    private static final class Detection {
        final String tier;
        final PersonaCard currentCard;
        final int score;
        final String reason;

        Detection(String tier, PersonaCard currentCard, int score, String reason) {
            this.tier = tier;
            this.currentCard = currentCard;
            this.score = score;
            this.reason = reason;
        }

        boolean matched() {
            return !TextUtils.isEmpty(tier) && currentCard != null && score >= 6;
        }

        static Detection none(String reason) {
            return new Detection("", null, 0, reason);
        }
    }

    private static final class PromptSections {
        final String introPrefix;
        final String description;
        final String personalityMarker;
        final String personalityBody;
        final String scenarioMarker;
        final String scenarioBody;
        final String creatorMarker;
        final String creatorBody;
        final String suffix;

        private PromptSections(String introPrefix,
                               String description,
                               String personalityMarker,
                               String personalityBody,
                               String scenarioMarker,
                               String scenarioBody,
                               String creatorMarker,
                               String creatorBody,
                               String suffix) {
            this.introPrefix = introPrefix;
            this.description = description;
            this.personalityMarker = personalityMarker;
            this.personalityBody = personalityBody;
            this.scenarioMarker = scenarioMarker;
            this.scenarioBody = scenarioBody;
            this.creatorMarker = creatorMarker;
            this.creatorBody = creatorBody;
            this.suffix = suffix;
        }

        boolean isValid() {
            return !TextUtils.isEmpty(introPrefix)
                    && !TextUtils.isEmpty(personalityMarker)
                    && !TextUtils.isEmpty(scenarioMarker)
                    && !TextUtils.isEmpty(creatorMarker);
        }

        static PromptSections parse(String text) {
            if (TextUtils.isEmpty(text)) {
                return new PromptSections("", "", "", "", "", "", "", "", "");
            }
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
            String personalityMarker = findMarker(normalized, "\n\n性格特征：\n", "\n\n你的性格特征：\n", "\n\n性格特征:\n", "\n\n你的性格特征:\n");
            if (TextUtils.isEmpty(personalityMarker)) {
                return new PromptSections("", "", "", "", "", "", "", "", "");
            }
            int personalityStart = normalized.indexOf(personalityMarker);
            int introSplit = normalized.indexOf("\n\n");
            if (introSplit < 0 || introSplit >= personalityStart) {
                return new PromptSections("", "", "", "", "", "", "", "", "");
            }
            String scenarioMarker = findMarkerAfter(normalized, personalityStart + personalityMarker.length(),
                    "\n\n场景设定：\n", "\n\n当前场景设定：\n", "\n\n场景设定:\n", "\n\n当前场景设定:\n");
            if (TextUtils.isEmpty(scenarioMarker)) {
                return new PromptSections("", "", "", "", "", "", "", "", "");
            }
            int scenarioStart = normalized.indexOf(scenarioMarker, personalityStart + personalityMarker.length());

            String creatorMarker = findMarkerAfter(normalized, scenarioStart + scenarioMarker.length(),
                    "\n\n重要提示：\n", "\n\n重要提示:\n");
            if (TextUtils.isEmpty(creatorMarker)) {
                return new PromptSections("", "", "", "", "", "", "", "", "");
            }
            int creatorStart = normalized.indexOf(creatorMarker, scenarioStart + scenarioMarker.length());

            String introPrefix = normalized.substring(0, introSplit + 2);
            String description = normalized.substring(introSplit + 2, personalityStart);
            String personalityBody = normalized.substring(personalityStart + personalityMarker.length(), scenarioStart);
            String scenarioBody = normalized.substring(scenarioStart + scenarioMarker.length(), creatorStart);
            String creatorBody = normalized.substring(creatorStart + creatorMarker.length());
            return new PromptSections(
                    introPrefix,
                    description,
                    personalityMarker,
                    personalityBody,
                    scenarioMarker,
                    scenarioBody,
                    creatorMarker,
                    creatorBody,
                    ""
            );
        }

        private static String findMarker(String text, String... candidates) {
            for (String candidate : candidates) {
                if (text.contains(candidate)) {
                    return candidate;
                }
            }
            return "";
        }

        private static String findMarkerAfter(String text, int fromIndex, String... candidates) {
            for (String candidate : candidates) {
                if (text.indexOf(candidate, fromIndex) >= 0) {
                    return candidate;
                }
            }
            return "";
        }
    }
}
