## Terrakube GitHub codespaces.

GitHub Codespaces

This page contains the configuration for a development container using GitHub Codespaces that provides a consistent environment for working with Terrakube.

The Dev Containers setup includes all the necessary tools and dependencies to develop both the Java backend, TypeScript frontend components and includes terraform CLI.
Features

* Java 25 (Liberica)
* Maven 3.9.9
* Node.js 24.x with Yarn
* VS Code extensions for Java, JavaScript/TypeScript

Prerequisites

* GitHub Account

Prepare project

The first step is to create a GitHub fork to work with Terrakube

<img width="763" height="186" alt="image" src="https://github.com/user-attachments/assets/f945a48b-4915-44f6-a2e2-aba67a28cd31" />

Once you have created a fork you need to create a new branch to work for any new Terrakube feature

<img width="578" height="568" alt="image" src="https://github.com/user-attachments/assets/ed41d850-a903-4fcd-8216-839c07f640c5" />

Open a new GitHub workspace using "new with options"

<img width="506" height="366" alt="image" src="https://github.com/user-attachments/assets/5f918b51-cdf2-4af7-85a4-2335a21b2a41" />

Select a 4 CPU configuration like the following.

<img width="709" height="434" alt="image" src="https://github.com/user-attachments/assets/254799e9-fc67-4f41-ac86-b3d3982762e1" />

Dev Containers setup will take a couple of minutes.

<img width="1488" height="466" alt="image" src="https://github.com/user-attachments/assets/82b18842-2655-4e6a-842a-2fc4b165d821" />

Once the environment setup is completed, you will see the following message

<img width="1417" height="311" alt="image" src="https://github.com/user-attachments/assets/70d89f7b-86c1-47a0-9048-7d52c5da5033" />

Now we can run the new development environment

<img width="411" height="366" alt="image" src="https://github.com/user-attachments/assets/125ca36d-a195-4168-b1ad-145eb3961da4" />

You need to accept using the Java "Standard Mode".

<img width="468" height="134" alt="image" src="https://github.com/user-attachments/assets/55959647-fb84-4480-bc9d-d5dfc8acabd6" />

The Dev Containers environment will download all the maven dependencies, this could take a couple of minutes

<img width="501" height="156" alt="image" src="https://github.com/user-attachments/assets/269031b2-7469-459d-af39-04172b9ebff6" />

Once all dependencies are downloaded, you can see the four component running like the following

<img width="405" height="236" alt="image" src="https://github.com/user-attachments/assets/b53db619-81d7-468a-8089-ad0cd9a829e0" />

If not you can simply start the ui, api, registry and executor one by one using the following menu

<img width="403" height="188" alt="image" src="https://github.com/user-attachments/assets/50d7e2f7-8112-4ab8-88fb-9bcdd6eaa9b2" />

After we have the components running, we need to change the port configuration, we need to make sure Dex, the api, registry and ui are using public ports.

<img width="1341" height="314" alt="image" src="https://github.com/user-attachments/assets/eba70773-6a70-41bf-b0da-f7857b8a3f27" />

Example:

<img width="1390" height="312" alt="image" src="https://github.com/user-attachments/assets/51dcbf89-8bbe-4e51-bcf3-1e7516a42074" />

In the end it should look like this using public ports:

<img width="1390" height="312" alt="image" src="https://github.com/user-attachments/assets/18daccf3-cb00-4faa-a9c0-fc324a58c6b6" />

The development environment automatically creates "DEVCONTAINER.md" that contains all the URL for each component.

<img width="455" height="497" alt="image" src="https://github.com/user-attachments/assets/895491b1-9f73-4988-8b6e-b647b5367816" />

You will need to visit the URL for dex, ui, api and registry in order to accept that we need to use a public port for our environment.

<img width="599" height="400" alt="image" src="https://github.com/user-attachments/assets/123b9ba0-fc39-4e0d-a15b-374e0133ed43" />

You will see a message like this and click "continue"

<img width="607" height="600" alt="image" src="https://github.com/user-attachments/assets/33f926eb-3b40-4313-bdcb-6da3cc8c5ab5" />

Once you click continue

<img width="728" height="439" alt="image" src="https://github.com/user-attachments/assets/b1be27ec-1f6c-4c35-9e3e-eef7a8c17d0a" />

You need to do the same for the other components: api, registry, executor and dex

<img width="728" height="439" alt="image" src="https://github.com/user-attachments/assets/2d43ec01-695d-4130-9aa5-8ec57e585f9e" />

Now you can go back to the UI and click "Sign in"

<img width="426" height="320" alt="image" src="https://github.com/user-attachments/assets/2ad9ccaa-7244-486d-bf67-dcc8882536e7" />

This will redirect you to dex where you can use "admin@example.com" and "admin" to complete the login.

<img width="741" height="269" alt="image" src="https://github.com/user-attachments/assets/dee55756-9c1f-4e68-95fa-c675cceb15dd" />

Grant Dex access

<img width="1205" height="500" alt="image" src="https://github.com/user-attachments/assets/fd2e033d-8375-4bf6-a8c7-22631bc60e7b" />

Now you can start testing the development environment and do any required changes

<img width="1718" height="671" alt="image" src="https://github.com/user-attachments/assets/cfb05782-702b-42f9-95ab-e2ef7d65d76d" />

If you need to update the code for any java component, you can simply click "Stop"

<img width="600" height="228" alt="image" src="https://github.com/user-attachments/assets/33d2b784-c714-4cb2-adb6-b91b29a1774d" />

Start a new instance

<img width="493" height="165" alt="image" src="https://github.com/user-attachments/assets/f4ddbb1d-dbcb-4a92-99c9-b196cbb65505" />

Once all the changes are completed, we pick the files an push the changes to our feature branch

<img width="795" height="324" alt="image" src="https://github.com/user-attachments/assets/b077cc33-0cf8-481d-a6a3-089b500a6eea" />

You can return to GitHub and create a new pull request to the Terrakube main repository

<img width="1043" height="374" alt="image" src="https://github.com/user-attachments/assets/34ba80e3-728c-4a1b-8c31-9c4b777268a8" />

In the end the pull request will look like this.

<img width="1193" height="703" alt="image" src="https://github.com/user-attachments/assets/30df9e2a-6127-4372-848e-cb9fd5c7ee8e" />

Now you have completed your open source contribution to Terrakube 🙂

Finally you can go to GitHub and delete your codespace.

<img width="430" height="678" alt="image" src="https://github.com/user-attachments/assets/453c88d5-ff41-4b06-8dd0-4e5ea3c0124a" />



