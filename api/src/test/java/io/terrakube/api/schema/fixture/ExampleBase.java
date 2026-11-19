package io.terrakube.api.schema.fixture;

import jakarta.persistence.Column;
import java.util.Date;

public class ExampleBase {

    @Column(name = "created_date")
    private Date createdDate;
}
