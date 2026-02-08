FROM golang:1.24-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -o registry-go main.go

FROM alpine:latest

RUN apk add --no-cache git openssh-client ca-certificates

WORKDIR /app

COPY --from=builder /app/registry-go .

EXPOSE 8080

CMD ["./registry-go"]
