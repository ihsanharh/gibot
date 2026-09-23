package com.ihsanharh.gibot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class CatalogManagerTest {

    private CatalogManager catalogManager;

    @BeforeEach
    public void setup() {
        catalogManager = new CatalogManager();
    }

    @Test
    public void testZeroGiftTokensChecker() {
        // Modal form when account has 0 gift tokens: returns ONLY ONE BUTTON: "Buy Gifts"
        String zeroTokensForm = """
        {
            "type": "form",
            "title": "Gifts",
            "content": "",
            "buttons": [
                {
                    "text": "§eBuy Gifts"
                }
            ]
        }
        """;

        CatalogManager.ParsedFormInfo info = catalogManager.processForm(zeroTokensForm, "TestBot", null);

        assertTrue(info.isMainForm(), "Form with only 'Buy Gifts' button must be recognized as main /gift form");
        assertTrue(info.isNoTokens(), "Must flag noTokens when only 'Buy Gifts' button is returned");
        assertTrue(info.hasNoGiftTokens(), "hasNoGiftTokens() must return true");
        assertEquals(0, info.getTokenBalance(), "Token balance must be 0");
        assertEquals(0, catalogManager.getData().getAccountTokenBalance(), "Account token balance must be 0");
        assertTrue(info.getSubcategories().isEmpty(), "No subcategories should be present");
        assertTrue(info.getItems().isEmpty(), "No items should be present");
    }

    @Test
    public void testZeroTokensPatternInBuyGiftsButton() {
        // Modal form with Buy Gifts button explicitly showing 0 Gift Tokens Available
        String formWithZeroPattern = """
        {
            "type": "form",
            "title": "Gifts",
            "content": "",
            "buttons": [
                {
                    "text": "§eBuy Gifts\\n§70 Gift Tokens Available"
                }
            ]
        }
        """;

        CatalogManager.ParsedFormInfo info = catalogManager.processForm(formWithZeroPattern, "TestBot", null);

        assertTrue(info.isMainForm());
        assertTrue(info.hasNoGiftTokens());
        assertEquals(0, info.getTokenBalance());
    }

    @Test
    public void testFormWithAvailableTokens() {
        // Modal form when account has tokens (e.g. 5 tokens) and category buttons
        String normalForm = """
        {
            "type": "form",
            "title": "Gifts",
            "content": "",
            "buttons": [
                {
                    "text": "§eBuy Gifts\\n§75 Gift Tokens Available"
                },
                {
                    "text": "Hats\\n§7View all hats"
                },
                {
                    "text": "Costumes\\n§7View all costumes"
                }
            ]
        }
        """;

        CatalogManager.ParsedFormInfo info = catalogManager.processForm(normalForm, "TestBot", null);

        assertTrue(info.isMainForm());
        assertFalse(info.hasNoGiftTokens(), "Account has 5 tokens, must not flag no tokens");
        assertEquals(5, info.getTokenBalance());
        assertEquals(2, info.getSubcategories().size());
    }

    @Test
    public void testExactMatchingItemName() {
        // Subcategory form with items
        String subcategoryForm = """
        {
            "type": "form",
            "title": "Hats",
            "content": "",
            "buttons": [
                {
                    "text": "Cardboard Box\\n§a1 Token"
                },
                {
                    "text": "Axe Head\\n§a2 Tokens"
                },
                {
                    "text": "Go back"
                }
            ]
        }
        """;

        catalogManager.processForm(subcategoryForm, "TestBot", "Hats");

        // Exact match (case-insensitive) succeeds
        Optional<CatalogManager.ItemEntry> cardboardBox = catalogManager.findItem("Cardboard Box");
        assertTrue(cardboardBox.isPresent());
        assertEquals("Cardboard Box", cardboardBox.get().getName());

        Optional<CatalogManager.ItemEntry> lowerCardboard = catalogManager.findItem("cardboard box");
        assertTrue(lowerCardboard.isPresent());

        Optional<CatalogManager.ItemEntry> upperAxe = catalogManager.findItem("AXE HEAD");
        assertTrue(upperAxe.isPresent());

        // Partial match MUST fail / be rejected
        Optional<CatalogManager.ItemEntry> partialCardboard = catalogManager.findItem("cardboard");
        assertTrue(partialCardboard.isEmpty(), "Partial item name 'cardboard' must be rejected");

        Optional<CatalogManager.ItemEntry> partialBox = catalogManager.findItem("box");
        assertTrue(partialBox.isEmpty(), "Partial item name 'box' must be rejected");

        Optional<CatalogManager.ItemEntry> partialAxe = catalogManager.findItem("axe");
        assertTrue(partialAxe.isEmpty(), "Partial item name 'axe' must be rejected");

        // Extra words MUST fail / be rejected
        Optional<CatalogManager.ItemEntry> extraWords = catalogManager.findItem("cardboard box hat");
        assertTrue(extraWords.isEmpty(), "Extra words 'cardboard box hat' must be rejected");
    }
}
