# How to contribute to Terrakube
First, thanks for taking the time to contribute to our project! There are many ways you can help out.

### Questions

If you have a question that needs an answer, [create an issue](https://github.com/terrakube-io/terrakube/issues/new), and label it as a question.

### Issues for bugs or feature requests

If you encounter any bugs in the code, or want to request a new feature or enhancement, please [create an issue](https://github.com/terrakube-io/terrakube/issues/new) to report it. Kindly add a label to indicate what type of issue it is.

### Contribute Code

We welcome pull requests for bug fixes. For a new feature or a material change,
please create an issue first so we can discuss the approach.

If you want to develop or test Terrakube locally, please read the
[development guide](.devcontainer/README.md). It covers macOS, Linux, Windows,
and WSL using Dev Containers.

For a browser-hosted development environment, see the
[GitHub Codespaces guide](development.md).

### Test before opening a pull request

Please test your change locally before opening a pull request. Run the checks
that apply to the files you changed:

```sh
# Backend or shared changes
mvn -B verify -Dspring-boot.build-image.skip=true

# UI changes
cd ui
yarn install --immutable
yarn lint:modules:check
yarn format:modules:check
yarn build
```

For a behavior change, use the local Dev Container to verify the affected flow.
In the pull request, briefly say what you ran and checked. If something cannot
be tested locally, say why so reviewers can help.

### Create a pull request

- Keep each pull request focused and describe both the problem and the change.
- Link the related issue when one exists.
- Add or update tests and documentation when the change needs them.
- Include screenshots or recordings for user-interface changes.
- Follow the project's [commit-message best practices](https://github.com/trein/dev-best-practices/wiki/Git-Commit-Best-Practices).

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
