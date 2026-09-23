# How to contribute to Terrakube
First, thanks for taking the time to contribute to our project! There are many ways you can help out.

### Questions

If you have a question that needs an answer, [create an issue](https://github.com/terrakube-io/terrakube/issues/new), and label it as a question.

### Issues for bugs or feature requests

If you encounter any bugs in the code, or want to request a new feature or enhancement, please [create an issue](https://github.com/terrakube-io/terrakube/issues/new) to report it. Kindly add a label to indicate what type of issue it is.

### Contribute Code
We welcome your pull requests for bug fixes. To implement something new, please create an issue first, so we can discuss it together.

If you want to develop or test Terrakube please check the [development guide](development.md)

***Creating a Pull Request***
Please follow [best practices](https://github.com/trein/dev-best-practices/wiki/Git-Commit-Best-Practices) for creating git commits.

***Continuous integration***

The project has a limited monthly GitHub Actions budget, so please help keep CI cheap:

- Open the pull request as a **draft** while you are still working on it; CI for fork pull requests does not run on drafts. Mark it *Ready for review* when it is done.
- Batch your changes and squash/rebase locally instead of pushing many small commits. A new push cancels the previous run for the same pull request, but every push still starts a new one.
- Changes limited to `ui/**`, `docs/**` or Markdown files do not run the backend build; the UI build only runs when `ui/**` changes.
- Workflows from first-time and outside contributors wait for a maintainer to approve them.
- Pull requests from forks run a compile-only backend check. The full Maven build with tests runs automatically for maintainers; for other contributors a maintainer starts it once by adding the `full-ci` label (remove and re-add the label to run it again).
