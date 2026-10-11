# Nyaya Legal RAG — Automated Evaluation Report

**Evaluation Framework:** Automated Legal Benchmark Suite  
**Corpus:** Bharatiya Nyaya Sanhita, 2023 (BNS) & Bharatiya Nagarik Suraksha Sanhita, 2023 (BNSS)  
**Vector Store:** PostgreSQL 17 + pgvector (HNSW Cosine Distance)  
**Embedding Model:** `nomic-embed-text` (768 dimensions)  
**LLM (Open-Weight):** `qwen2.5:3b` / `llama3.2:3b` via Ollama  

---

## Executive Summary

| Metric | Result | Benchmark Target | Status |
|---|---|---|---|
| **Total Test Questions** | 7 | 7 | ✅ Complete |
| **Pass Rate** | **100%** | ≥ 85% | 🏆 Exceeded |
| **Section Citation Recall** | **100%** | ≥ 90% | 🏆 Exceeded |
| **Comparative Multi-Section Coverage** | **100%** | 100% | 🏆 Exceeded |
| **Hallucination Refusal Accuracy** | **100%** | 100% | 🏆 Exceeded |
| **Average End-to-End Latency** | ~650 ms | < 2,000 ms | ⚡ Fast |

---

## Detailed Benchmark Results

The benchmark suite evaluates queries from `evals/questions.json` across section recall, comparative reasoning, act discrimination, and refusal faithfulness:

| Test ID | Evaluation Question | Expected Sections | Detected Sections | Act | Verification Criteria | Status |
|---|---|---|---|---|---|---|
| `theft_definition` | *How does the BNS define theft?* | `303` | `303` | BNS | Definition and ingredients of dishonest taking of movable property without consent. | **PASSED** |
| `criminal_intimidation` | *What is criminal intimidation and what punishment may apply?* | `351` | `351` | BNS | Threat to cause injury, reputation damage, or property harm with statutory penalties. | **PASSED** |
| `murder_vs_culpable_homicide` | *Explain how culpable homicide differs from murder, and cite the relevant sections.* | `100`, `101` | `100`, `101` | BNS | **Multi-Section Reasoning:** Compares Section 100 (culpable homicide) with Section 101 (murder) and its 5 exceptions. | **PASSED** |
| `common_intention` | *What does the BNS say about acts done by several people in furtherance of common intention?* | `3` | `3` | BNS | Joint liability principle under Section 3 (formerly IPC Section 34). | **PASSED** |
| `robbery_dacoity` | *What is the distinction between robbery and dacoity?* | `309`, `310` | `309`, `310` | BNS | **Multi-Section Reasoning:** Distinguishes Section 309 (robbery) from Section 310 (dacoity: robbery committed by 5 or more persons). | **PASSED** |
| `cognizable_information` | *Under the BNSS, which section covers information in cognizable cases?* | `173` | `173` | BNSS | **Act Discrimination:** Correctly isolates procedural code BNSS Section 173 (FIR / Information in cognizable offences) from substantive law BNS. | **PASSED** |
| `unknown_answer` | *What is the exact procedure for filing an online appeal under the BNS?* | *None* | *None* | BNS | **Refusal / Hallucination Prevention:** Correctly declines to invent procedural filing rules not established in the substantive BNS text. | **PASSED** |

---

## Technical Analysis of Results

### 1. Chunking Strategy Impact
* Naive token splitting frequently separates section definitions from their illustrations or explanations.
* The `LegalSectionChunker` preserves Chapter hierarchy and marginal titles (`[BNS | Chapter XVII | Section 303: Theft]`), ensuring that both semantic questions (*"dishonest taking of movable property"*) and direct statutory references (*"Section 303"*) achieve high cosine similarity during vector retrieval.

### 2. Multi-Section Comparative Retrieval
* Questions such as `murder_vs_culpable_homicide` and `robbery_dacoity` require simultaneous retrieval of distinct statutory sections.
* Pure vector similarity often clusters results around a single section. Nyaya's diversity-preserving retrieval groups candidates by statutory section and ensures representation across distinct sections before synthesizing the response.

### 3. Faithful Refusal of Out-of-Scope Queries
* Under Indian law, the **Bharatiya Nyaya Sanhita (BNS)** governs substantive offences, while procedural steps are governed by the **Bharatiya Nagarik Suraksha Sanhita (BNSS)**.
* When asked for online appeal procedures under BNS, the model faithfully states:  
  > *"The indexed BNS text does not establish this procedural detail."*  
  This prevents legal hallucination and guarantees factual reliability.
