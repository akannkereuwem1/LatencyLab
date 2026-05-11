# Agent Directives

1. **Context & Documentation First**: 
   Always make use of the existing directories and files when needed. Specifically, check the `.kiro/steering/` directory (which includes `product.md`, `structure.md`, and `tech.md`) and the `.kiro/specs/` directory to understand the project architecture, technology stack, and product requirements before making decisions or taking actions.

2. **Sequential Execution**: 
   All tasks MUST ALWAYS be completed sequentially. Do not perform steps or tool calls in parallel. Ensure that each step is fully complete before moving on to the next one.

3. **Automatic Commit**:
   After any code change (additions, modifications, or deletions), automatically stage and commit the changes with a concise commit message reflecting the nature of the change. This eliminates the need for the user to request commits manually.
