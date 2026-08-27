package io.terrakube.api.rs.job;

/**
 * {@link #value} is what gets persisted in {@code Job.via} and shown in the UI; it must stay
 * stable regardless of the constant name so historical job records keep matching.
 */
public enum JobVia {
   UI("UI"),
   CLI("CLI"),
   GITHUB("Github"),
   GITLAB("Gitlab"),
   BITBUCKET("Bitbucket"),
   AZURE_DEVOPS("AzureDevops"),
   SCHEDULE("Schedule");

   private final String value;

   JobVia(String value) {
      this.value = value;
   }

   public String getValue() {
      return value;
   }
}
